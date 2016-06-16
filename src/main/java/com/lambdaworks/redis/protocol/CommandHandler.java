// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis.protocol;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapMaker;
import com.lambdaworks.redis.ClientOptions;
import com.lambdaworks.redis.ConnectionEvents;
import com.lambdaworks.redis.RedisChannelHandler;
import com.lambdaworks.redis.RedisChannelWriter;
import com.lambdaworks.redis.RedisException;
import com.lambdaworks.redis.resource.ClientResources;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.local.LocalAddress;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.logging.InternalLogLevel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * A netty {@link ChannelHandler} responsible for writing redis commands and reading responses from the server.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Will Glozer
 */
@ChannelHandler.Sharable
public class CommandHandler<K, V> extends ChannelDuplexHandler implements RedisChannelWriter<K, V> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(CommandHandler.class);
    private static final WriteLogListener WRITE_LOG_LISTENER = new WriteLogListener();

    /**
     * When we encounter an unexpected IOException we look for these {@link Throwable#getMessage() messages} (because we have no
     * better way to distinguish) and log them at DEBUG rather than WARN, since they are generally caused by unclean client
     * disconnects rather than an actual problem.
     */
    private static final Set<String> SUPPRESS_IO_EXCEPTION_MESSAGES = ImmutableSet.of("Connection reset by peer", "Broken pipe",
            "Connection timed out");

    protected final ClientOptions clientOptions;
    protected final ClientResources clientResources;
    protected final Queue<RedisCommand<K, V, ?>> queue;
    protected final ReentrantLock writeLock = new ReentrantLock();

    // all access to the commandBuffer is synchronized
    protected volatile Deque<RedisCommand<K, V, ?>> commandBuffer = newCommandBuffer();
    protected final Deque<RedisCommand<K, V, ?>> transportBuffer = newCommandBuffer();
    protected ByteBuf buffer;
    protected RedisStateMachine<K, V> rsm;
    protected Channel channel;
    private volatile ConnectionWatchdog connectionWatchdog;

    // If TRACE level logging has been enabled at startup.
    private final boolean traceEnabled;

    // If DEBUG level logging has been enabled at startup.
    private final boolean debugEnabled;
    private final Reliability reliability;

    private LifecycleState lifecycleState = LifecycleState.NOT_CONNECTED;
    private RedisChannelHandler<K, V> redisChannelHandler;
    private Throwable connectionError;
    private String logPrefix;
    private boolean autoFlushCommands = true;
    private final Object stateLock = new Object();
    private final Map<RedisCommand<K, V, ?>, SentReceived> sentTimes = new MapMaker().concurrencyLevel(4).weakKeys().makeMap();

    /**
     * Initialize a new instance that handles commands from the supplied queue.
     *
     * @param clientOptions client options for this connection, must not be {@literal null}
     * @param clientResources client resources for this connection, must not be {@literal null}
     * @param queue The command queue, must not be {@literal null}
     */
    public CommandHandler(ClientOptions clientOptions, ClientResources clientResources, Queue<RedisCommand<K, V, ?>> queue) {
        checkArgument(clientOptions != null, "clientOptions must not be null");
        checkArgument(clientResources != null, "clientResources must not be null");
        checkArgument(queue != null, "queue must not be null");

        this.clientOptions = clientOptions;
        this.clientResources = clientResources;
        this.queue = queue;
        this.traceEnabled = logger.isTraceEnabled();
        this.debugEnabled = logger.isDebugEnabled();
        this.reliability = clientOptions.isAutoReconnect() ? Reliability.AT_LEAST_ONCE : Reliability.AT_MOST_ONCE;
    }

    /**
     *
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelRegistered(io.netty.channel.ChannelHandlerContext)
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        setState(LifecycleState.REGISTERED);
        buffer = ctx.alloc().directBuffer(8192 * 8);
        rsm = new RedisStateMachine<K, V>();
        synchronized (stateLock) {
            channel = ctx.channel();
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        releaseBuffer();

        if (lifecycleState == LifecycleState.CLOSED) {
            cancelCommands("Connection closed");
        }
        synchronized (stateLock) {
            channel = null;
        }
    }

    /**
     *
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelRead(io.netty.channel.ChannelHandlerContext, java.lang.Object)
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf input = (ByteBuf) msg;

        if (!input.isReadable() || input.refCnt() == 0 || buffer == null) {
            return;
        }

        try {
            buffer.writeBytes(input);

            if (traceEnabled) {
                logger.trace("{} Received: {}", logPrefix(), buffer.toString(Charset.defaultCharset()).trim());
            }

            decode(ctx, buffer);
        } finally {
            input.release();
        }
    }

    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer) throws InterruptedException {

        while (!queue.isEmpty()) {

            RedisCommand<K, V, ?> command = queue.peek();
            SentReceived sentReceived = sentTimes.get(command);

            if (debugEnabled) {
                logger.debug("{} Queue contains: {} commands", logPrefix(), queue.size());
            }

            if (sentReceived != null && sentReceived.firstResponse == -1) {
                sentReceived.firstResponse = nanoTime();
            }

            if (!rsm.decode(buffer, command, command.getOutput())) {
                return;
            }

            command = queue.poll();
            sentTimes.remove(command);
            recordLatency(command, sentReceived);

            command.complete();

            if (buffer != null && buffer.refCnt() != 0) {
                buffer.discardReadBytes();
            }
        }
    }

    private void recordLatency(RedisCommand<K, V, ?> command, SentReceived sentReceived) {
        if (sentReceived != null && channel != null && remote() != null
                && clientResources.commandLatencyCollector().isEnabled()) {
            long firstResponseLatency = nanoTime() - sentReceived.firstResponse;
            long completionLatency = nanoTime() - sentReceived.sent;
            clientResources.commandLatencyCollector().recordCommandLatency(local(), remote(), command.getType(),
                    firstResponseLatency, completionLatency);
        }
    }

    private SocketAddress remote() {
        return channel.remoteAddress();
    }

    private SocketAddress local() {
        if (channel.localAddress() != null) {
            return channel.localAddress();
        }
        return LocalAddress.ANY;
    }

    @Override
    public <T> RedisCommand<K, V, T> write(RedisCommand<K, V, T> command) {

        checkArgument(command != null, "command must not be null");

        try {
            /**
             * This lock causes safety for connection activation and somehow netty gets more stable and predictable performance
             * than without a lock and all threads are hammering towards writeAndFlush.
             */

            writeLock.lock();

            if (lifecycleState == LifecycleState.CLOSED) {
                throw new RedisException("Connection is closed");
            }

            if (commandBuffer.size() + queue.size() >= clientOptions.getRequestQueueSize()) {
                throw new RedisException("Request queue size exceeded: " + clientOptions.getRequestQueueSize()
                        + ". Commands are not accepted until the queue size drops.");
            }

            if ((channel == null || !isConnected()) && !clientOptions.isAutoReconnect()) {
                throw new RedisException(
                        "Connection is in a disconnected state and reconnect is disabled. Commands are not accepted.");
            }

            Channel channel = this.channel;
            if (autoFlushCommands) {

                if (channel != null && isConnected() && channel.isActive()) {
                    if (debugEnabled) {
                        logger.debug("{} write() writeAndFlush Command {}", logPrefix(), command);
                    }

                    if (reliability == Reliability.AT_MOST_ONCE) {
                        // cancel on exceptions and remove from queue, because there is no housekeeping
                        writeAndFlush(command).addListener(new AtMostOnceWriteListener(command, queue, sentTimes));
                    }

                    if (reliability == Reliability.AT_LEAST_ONCE) {
                        // commands are ok to stay within the queue, reconnect will retrigger them
                        writeAndFlush(command).addListener(WRITE_LOG_LISTENER);
                    }
                } else {

                    if (commandBuffer.contains(command) || queue.contains(command)) {
                        return command;
                    }

                    if (connectionError != null) {
                        if (debugEnabled) {
                            logger.debug("{} write() completing Command {} due to connection error", logPrefix(), command);
                        }
                        command.setException(connectionError);
                        command.complete();
                        return command;
                    }

                    bufferCommand(command);
                }
            } else {
                bufferCommand(command);
            }
        } finally {
            writeLock.unlock();
            if (debugEnabled) {
                logger.debug("{} write() done", logPrefix());
            }
        }

        return command;
    }

    private <T> void bufferCommand(RedisCommand<K, V, T> command) {
        if (debugEnabled) {
            logger.debug("{} write() buffering Command {}", logPrefix(), command);
        }
        commandBuffer.add(command);
    }

    protected boolean isConnected() {
        synchronized (lifecycleState) {
            return lifecycleState.ordinal() >= LifecycleState.CONNECTED.ordinal()
                    && lifecycleState.ordinal() < LifecycleState.DISCONNECTED.ordinal();
        }
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void flushCommands() {
        if (channel != null && isConnected()) {
            Queue<RedisCommand<K, V, ?>> queuedCommands;
            try {
                writeLock.lock();
                queuedCommands = (Queue) commandBuffer;
                commandBuffer = newCommandBuffer();
            } finally {
                writeLock.unlock();
            }

            if (reliability == Reliability.AT_MOST_ONCE) {
                // cancel on exceptions and remove from queue, because there is no housekeeping
                writeAndFlush(queuedCommands)
                        .addListener(new AtMostOnceWriteListener(queuedCommands, this.queue, sentTimes));
            }

            if (reliability == Reliability.AT_LEAST_ONCE) {
                // commands are ok to stay within the queue, reconnect will retrigger them
                writeAndFlush(queuedCommands).addListener(WRITE_LOG_LISTENER);
            }
        }
    }

    private ChannelFuture writeAndFlush(Collection<RedisCommand<K, V, ?>> commands) {

        if (debugEnabled) {
            logger.debug("{} write() writeAndFlush Commands {}", logPrefix(), commands);
        }

        transportBuffer.addAll(commands);
        return channel.writeAndFlush(commands);
    }

    private ChannelFuture writeAndFlush(RedisCommand<K, V, ?> command) {

        if (debugEnabled) {
            logger.debug("{} write() writeAndFlush Command {}", logPrefix(), command);
        }

        transportBuffer.add(command);
        return channel.writeAndFlush(command);
    }

    /**
     *
     * @see io.netty.channel.ChannelDuplexHandler#write(io.netty.channel.ChannelHandlerContext, java.lang.Object,
     *      io.netty.channel.ChannelPromise)
     */
    @Override
    @SuppressWarnings("unchecked")
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        if (msg instanceof RedisCommand) {
            writeSingleCommand(ctx, (RedisCommand<K, V, ?>) msg, promise);
            return;
        }

        if (msg instanceof Collection) {
            writeBatch(ctx, (Collection<RedisCommand<K, V, ?>>) msg, promise);
        }
    }

    private void writeSingleCommand(ChannelHandlerContext ctx, RedisCommand<K, V, ?> command, ChannelPromise promise)
            throws Exception {

        if (command.isCancelled()) {
            transportBuffer.remove(command);
            return;
        }

        queueCommand(command, promise);
        ctx.write(command, promise);
    }

    private void writeBatch(ChannelHandlerContext ctx, Collection<RedisCommand<K, V, ?>> msg, ChannelPromise promise)
            throws Exception {

        Collection<RedisCommand<K, V, ?>> commands = msg;
        Collection<RedisCommand<K, V, ?>> toWrite = commands;

        boolean cancelledCommands = false;
        for (RedisCommand<K, V, ?> command : commands) {
            if (command.isCancelled()) {
                cancelledCommands = true;
                break;
            }
        }

        if (cancelledCommands) {

            toWrite = new ArrayList<RedisCommand<K, V, ?>>(commands.size());

            for (RedisCommand<K, V, ?> command : commands) {

                if (command.isCancelled()) {
                    transportBuffer.remove(command);
                    continue;
                }

                toWrite.add(command);
                queueCommand(command, promise);
            }
        } else {

            for (RedisCommand<K, V, ?> command : toWrite) {
                queueCommand(command, promise);
            }
        }

        if (!toWrite.isEmpty()) {
            ctx.write(toWrite, promise);
        }
    }

    private void queueCommand(RedisCommand<K, V, ?> command, ChannelPromise promise) throws Exception {

        try {

            if (command.getOutput() == null) {
                // fire&forget commands are excluded from metrics
                command.complete();
            } else {

                sentTimes.put(command, new SentReceived(nanoTime()));
                queue.add(command);
            }
            transportBuffer.remove(command);
        } catch (Exception e) {
            command.setException(e);
            command.cancel(true);
            promise.setFailure(e);
            throw e;
        }
    }

    private long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {

        connectionWatchdog = null;
        logPrefix = null;
        if (debugEnabled) {
            logger.debug("{} channelActive()", logPrefix());
        }
        setStateIfNotClosed(LifecycleState.CONNECTED);

        if (ctx != null && ctx.pipeline() != null) {

            Map<String, ChannelHandler> map = ctx.pipeline().toMap();

            for (ChannelHandler handler : map.values()) {
                if (handler instanceof ConnectionWatchdog) {
                    connectionWatchdog = (ConnectionWatchdog) handler;
                }
            }
        }

        try {
            writeLock.lock();
            // Move queued commands to buffer before issuing any commands because of connection activation.
            // That's necessary to prepend queued commands first as some commands might get into the queue
            // after the connection was disconnected. They need to be prepended to the command buffer
            moveQueuedCommandsToCommandBuffer();
            activateCommandHandlerAndExecuteBufferedCommands(ctx);
        } catch (Exception e) {

            if (debugEnabled) {
                logger.debug("{} channelActive() ran into an exception", logPrefix());
            }

            if (clientOptions.isCancelCommandsOnReconnectFailure()) {
                reset();
            }

            throw e;
        } finally {
            writeLock.unlock();
        }

        super.channelActive(ctx);
        if (channel != null) {
            channel.eventLoop().submit(new Runnable() {
                @Override
                public void run() {
                    channel.pipeline().fireUserEventTriggered(new ConnectionEvents.Activated());
                }
            });
        }

        if (debugEnabled) {
            logger.debug("{} channelActive() done", logPrefix());
        }
    }

    private void moveQueuedCommandsToCommandBuffer() {

        List<RedisCommand<K, V, ?>> queuedCommands = drainCommands(queue);
        Collections.reverse(queuedCommands);

        List<RedisCommand<K, V, ?>> transportBufferCommands = drainCommands(transportBuffer);
        Collections.reverse(transportBufferCommands);

        // Queued commands first because they reached the queue before commands that are still in the transport buffer.
        queuedCommands.addAll(transportBufferCommands);

        logger.debug("{} moveQueuedCommandsToCommandBuffer {} command(s) added to buffer", logPrefix(), queuedCommands.size());
        for (RedisCommand<K, V, ?> command : queuedCommands) {
            commandBuffer.addFirst(command);
        }
    }

    private List<RedisCommand<K, V, ?>> drainCommands(Collection<RedisCommand<K, V, ?>> source) {

        List<RedisCommand<K, V, ?>> target = new ArrayList<RedisCommand<K, V, ?>>(source.size());
        target.addAll(source);
        source.removeAll(target);
        return target;
    }

    protected void activateCommandHandlerAndExecuteBufferedCommands(ChannelHandlerContext ctx) {

        try {
            writeLock.lock();
            connectionError = null;

            sentTimes.clear();

            if (debugEnabled) {
                logger.debug("{} activateCommandHandlerAndExecuteBufferedCommands {} command(s) buffered", logPrefix(), commandBuffer.size());
            }

            synchronized (stateLock) {
                channel = ctx.channel();
            }

            if (redisChannelHandler != null) {
                if (debugEnabled) {
                    logger.debug("{} activating channel handler", logPrefix());
                }
                // Commands after this line are executed (queue) and not buffered.
                setStateIfNotClosed(LifecycleState.ACTIVATING);
                redisChannelHandler.activated();
            }
            setStateIfNotClosed(LifecycleState.ACTIVE);

            flushCommands();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     *
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelInactive(io.netty.channel.ChannelHandlerContext)
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        if (debugEnabled) {
            logger.debug("{} channelInactive()", logPrefix());
        }

        try {
            writeLock.lock();
            setStateIfNotClosed(LifecycleState.DISCONNECTED);

            if (redisChannelHandler != null) {

                if (debugEnabled) {
                    logger.debug("{} deactivating channel handler", logPrefix());
                }

                setStateIfNotClosed(LifecycleState.DEACTIVATING);
                redisChannelHandler.deactivated();
            }

            setStateIfNotClosed(LifecycleState.DEACTIVATED);

            // Shift all commands to the commandBuffer so the queue is empty.
            // Allows to run onConnect commands before executing buffered commands
            commandBuffer.addAll(queue);
            queue.removeAll(commandBuffer);

        } finally {
            writeLock.unlock();
        }

        if (buffer != null) {
            rsm.reset();
            buffer.clear();
        }

        if (debugEnabled) {
            logger.debug("{} channelInactive() done", logPrefix());
        }
        super.channelInactive(ctx);
    }

    protected void setStateIfNotClosed(LifecycleState lifecycleState) {
        if (this.lifecycleState != LifecycleState.CLOSED) {
            setState(lifecycleState);
        }
    }

    protected void setState(LifecycleState lifecycleState) {
        synchronized (stateLock) {
            this.lifecycleState = lifecycleState;
        }
    }

    protected LifecycleState getState() {
        return lifecycleState;
    }

    private void cancelCommands(String message) {
        int size = 0;
        if (queue != null) {
            size += queue.size();
        }

        if (commandBuffer != null) {
            size += commandBuffer.size();
        }

        List<RedisCommand<K, V, ?>> toCancel = new ArrayList<RedisCommand<K, V, ?>>(size);

        if (queue != null) {
            toCancel.addAll(queue);
            queue.clear();
        }

        if (commandBuffer != null) {
            toCancel.addAll(commandBuffer);
            commandBuffer.clear();
        }
        sentTimes.clear();

        for (RedisCommand<K, V, ?> cmd : toCancel) {
            if (cmd.getOutput() != null) {
                cmd.getOutput().setError(message);
            }
            cmd.cancel(true);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

        InternalLogLevel logLevel = InternalLogLevel.WARN;

        if (!queue.isEmpty()) {
            RedisCommand<K, V, ?> command = queue.poll();
            sentTimes.remove(command);
            if (debugEnabled) {
                logger.debug("{} Storing exception in {}", logPrefix(), command);
            }
            logLevel = InternalLogLevel.DEBUG;
            command.setException(cause);
            command.complete();
        }

        if (channel == null || !channel.isActive() || !isConnected()) {
            if (debugEnabled) {
                logger.debug("{} Storing exception in connectionError", logPrefix());
            }
            logLevel = InternalLogLevel.DEBUG;
            connectionError = cause;
        }

        if (cause instanceof IOException && logLevel.ordinal() > InternalLogLevel.INFO.ordinal()) {
            logLevel = InternalLogLevel.INFO;
            if (SUPPRESS_IO_EXCEPTION_MESSAGES.contains(cause.getMessage())) {
                logLevel = InternalLogLevel.DEBUG;
            }
        }

        logger.log(logLevel, "{} Unexpected exception during request: {}", logPrefix, cause.toString(), cause);
    }

    /**
     * Close the connection.
     */
    @Override
    public void close() {

        if (debugEnabled) {
            logger.debug("{} close()", logPrefix());
        }

        if (lifecycleState == LifecycleState.CLOSED) {
            return;
        }

        setStateIfNotClosed(LifecycleState.CLOSED);
        Channel currentChannel = this.channel;
        if (currentChannel != null) {
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.PrepareClose());
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.Close());

            ChannelFuture close = currentChannel.pipeline().close();
            if (currentChannel.isOpen()) {
                close.syncUninterruptibly();
            }
        } else if (connectionWatchdog != null) {
            connectionWatchdog.prepareClose(new ConnectionEvents.PrepareClose());
        }
    }

    private void releaseBuffer() {
        if (buffer != null) {
            buffer.release();
            buffer = null;
        }
    }

    public boolean isClosed() {
        return lifecycleState == LifecycleState.CLOSED;
    }

    /**
     * Reset the writer state. Queued commands will be canceled and the internal state will be reset. This is useful when the
     * internal state machine gets out of sync with the connection.
     */
    @Override
    public void reset() {
        if (debugEnabled) {
            logger.debug("{} reset()", logPrefix());
        }
        try {
            writeLock.lock();
            cancelCommands("Reset");
        } finally {
            writeLock.unlock();
        }

        if (buffer != null) {
            rsm.reset();
            buffer.clear();
        }
    }

    /**
     * Reset the command-handler to the initial not-connected state.
     */
    public void initialState() {

        setState(LifecycleState.NOT_CONNECTED);
        queue.clear();
        commandBuffer.clear();

        Channel currentChannel = this.channel;
        if (currentChannel != null) {
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.PrepareClose());
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.Close());
            currentChannel.pipeline().close();
        }
    }

    @Override
    public void setRedisChannelHandler(RedisChannelHandler<K, V> redisChannelHandler) {
        this.redisChannelHandler = redisChannelHandler;
    }

    @Override
    public void setAutoFlushCommands(boolean autoFlush) {
        synchronized (stateLock) {
            this.autoFlushCommands = autoFlush;
        }
    }

    protected String logPrefix() {
        if (logPrefix != null) {
            return logPrefix;
        }
        StringBuffer buffer = new StringBuffer(64);
        buffer.append('[').append(ChannelLogDescriptor.logDescriptor(channel)).append(']');
        return logPrefix = buffer.toString();
    }

    private ArrayDeque<RedisCommand<K, V, ?>> newCommandBuffer() {
        return new ArrayDeque<RedisCommand<K, V, ?>>(512);
    }

    public enum LifecycleState {
        NOT_CONNECTED, REGISTERED, CONNECTED, ACTIVATING, ACTIVE, DISCONNECTED, DEACTIVATING, DEACTIVATED, CLOSED,
    }

    private enum Reliability {
        AT_MOST_ONCE, AT_LEAST_ONCE;
    }

    private static class AtMostOnceWriteListener<K, V, T> implements ChannelFutureListener {

        private final Collection<RedisCommand<K, V, T>> sentCommands;
        private final Queue<?> queue;
        private final Map<RedisCommand<K, V, T>, SentReceived> sentTimes;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public AtMostOnceWriteListener(RedisCommand<K, V, T> sentCommand, Queue<?> queue,
                Map<RedisCommand<K, V, T>, SentReceived> sentTimes) {
            this((Collection) ImmutableList.of(sentCommand), queue, sentTimes);
        }

        public AtMostOnceWriteListener(Collection<RedisCommand<K, V, T>> sentCommand, Queue<?> queue,
                Map<RedisCommand<K, V, T>, SentReceived> sentTimes) {
            this.sentCommands = sentCommand;
            this.queue = queue;
            this.sentTimes = sentTimes;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            future.await();
            if (future.cause() != null) {

                for (RedisCommand<?, ?, ?> sentCommand : sentCommands) {
                    sentCommand.setException(future.cause());
                    sentCommand.cancel(true);
                }

                queue.removeAll(sentCommands);

                for (RedisCommand<K, V, T> sentCommand : sentCommands) {
                    sentTimes.remove(sentCommand);
                }
            }
        }
    }

    /**
     * A generic future listener which logs unsuccessful writes.
     *
     */
    static class WriteLogListener implements GenericFutureListener<Future<Void>> {

        @Override
        public void operationComplete(Future<Void> future) throws Exception {
            Throwable cause = future.cause();
            if (!future.isSuccess() && !(cause instanceof ClosedChannelException)) {

                String message = "Unexpected exception during request: {}";
                InternalLogLevel logLevel = InternalLogLevel.WARN;

                if (cause instanceof IOException && SUPPRESS_IO_EXCEPTION_MESSAGES.contains(cause.getMessage())) {
                    logLevel = InternalLogLevel.DEBUG;
                }

                logger.log(logLevel, message, cause.toString(), cause);
            }
        }
    }

    static class SentReceived {

        final long sent;
        long firstResponse = -1;

        public SentReceived(long sent) {
            this.sent = sent;
        }
    }
}
