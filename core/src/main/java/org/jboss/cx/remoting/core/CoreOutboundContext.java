package org.jboss.cx.remoting.core;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import org.jboss.cx.remoting.Context;
import org.jboss.cx.remoting.FutureReply;
import org.jboss.cx.remoting.RemoteExecutionException;
import org.jboss.cx.remoting.RemotingException;
import org.jboss.cx.remoting.RequestCompletionHandler;
import org.jboss.cx.remoting.CloseHandler;
import org.jboss.cx.remoting.util.AtomicStateMachine;
import org.jboss.cx.remoting.util.CollectionUtil;
import org.jboss.cx.remoting.log.Logger;
import org.jboss.cx.remoting.spi.protocol.ContextIdentifier;
import org.jboss.cx.remoting.spi.protocol.RequestIdentifier;

/**
 *
 */
public final class CoreOutboundContext<I, O> {
    private static final Logger log = Logger.getLogger(CoreOutboundContext.class);

    private final CoreEndpoint endpoint;
    private final CoreSession session;
    private final ContextIdentifier contextIdentifier;

    private final ConcurrentMap<Object, Object> contextMap = CollectionUtil.concurrentMap();
    private final ConcurrentMap<RequestIdentifier, CoreOutboundRequest<I, O>> requests = CollectionUtil.concurrentMap();
    private final AtomicStateMachine<State> state = AtomicStateMachine.start(State.UP);
    private final Context<I, O> userContext = new UserContext();

    public CoreOutboundContext(final CoreSession session, final ContextIdentifier contextIdentifier) {
        this.session = session;
        this.contextIdentifier = contextIdentifier;
        endpoint = session.getEndpoint();
    }

    private enum State {
        UP,
        STOPPING,
        DOWN,
    }

    // Request management

    void dropRequest(final RequestIdentifier requestIdentifier, final CoreOutboundRequest<I, O> coreOutboundRequest) {
        requests.remove(requestIdentifier, coreOutboundRequest);
    }

    // Outbound protocol messages

    boolean sendCancelRequest(final RequestIdentifier requestIdentifier, final boolean mayInterrupt) {
        if (state.inHold(State.UP)) try {
            return session.sendCancelRequest(contextIdentifier, requestIdentifier, mayInterrupt);
        } finally {
            state.release();
        } else {
            return false;
        }
    }

    void sendRequest(final RequestIdentifier requestIdentifier, final I request, final Executor streamExecutor) throws RemotingException {
        session.sendRequest(contextIdentifier, requestIdentifier, request, streamExecutor);
    }

    // Inbound protocol messages

    @SuppressWarnings ({"unchecked"})
    void receiveCloseContext() {
        final CoreOutboundRequest[] requestArray;
        if (! state.transition(State.UP, State.STOPPING)) {
            return;
        }
        requestArray = requests.values().toArray(empty);
        for (CoreOutboundRequest<I, O> request : requestArray) {
            request.receiveClose();
        }
        session.removeContext(contextIdentifier);
    }

    void receiveCancelAcknowledge(RequestIdentifier requestIdentifier) {
        final CoreOutboundRequest<I, O> request = requests.get(requestIdentifier);
        if (request != null) {
            request.receiveCancelAcknowledge();
        }
    }

    void receiveReply(RequestIdentifier requestIdentifier, O reply) {
        final CoreOutboundRequest<I, O> request = requests.get(requestIdentifier);
        if (request != null) {
            request.receiveReply(reply);
        }
    }

    void receiveException(RequestIdentifier requestIdentifier, RemoteExecutionException exception) {
        final CoreOutboundRequest<I, O> request = requests.get(requestIdentifier);
        if (request != null) {
            request.receiveException(exception);
        } else {
            log.trace("Received an exception for an unknown request (%s)", requestIdentifier);
        }
    }

    // Other protocol-related

    RequestIdentifier openRequest() throws RemotingException {
        return session.openRequest(contextIdentifier);
    }

    // Getters

    Context<I,O> getUserContext() {
        return userContext;
    }

    // Other mgmt

    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            receiveCloseContext();
            log.trace("Leaked a context instance: %s", this);
        }
    }

    public final class UserContext implements Context<I, O> {

        private UserContext() {
        }

        public void close() throws RemotingException {
            receiveCloseContext();
        }

        public void addCloseHandler(final CloseHandler<Context<I, O>> closeHandler) {
            // todo ...
        }

        private FutureReply<O> doSend(final I request, final QueueExecutor queueExecutor) throws RemotingException {
            final RequestIdentifier requestIdentifier;
            requestIdentifier = openRequest();
            final CoreOutboundRequest<I, O> outboundRequest = new CoreOutboundRequest<I, O>(CoreOutboundContext.this, requestIdentifier);
            requests.put(requestIdentifier, outboundRequest);
            // Request must be sent *after* the identifier is registered in the map
            sendRequest(requestIdentifier, request, queueExecutor);
            final FutureReply<O> futureReply = outboundRequest.getFutureReply();
            futureReply.addCompletionNotifier(new RequestCompletionHandler<O>() {
                public void notifyComplete(final FutureReply<O> futureReply) {
                    queueExecutor.shutdown();
                }
            });
            return futureReply;
        }

        public O invokeInterruptibly(final I request) throws RemotingException, RemoteExecutionException, InterruptedException {
            state.requireHold(State.UP);
            try {
                final QueueExecutor queueExecutor = new QueueExecutor();
                final FutureReply<O> futureReply = doSend(request, queueExecutor);
                // todo - find a safe way to make this interruptable
                queueExecutor.runQueue();
                return futureReply.getInterruptibly();
            } finally {
                state.release();
            }
        }

        public O invoke(final I request) throws RemotingException, RemoteExecutionException {
            state.requireHold(State.UP);
            try {
                final QueueExecutor queueExecutor = new QueueExecutor();
                final FutureReply<O> futureReply = doSend(request, queueExecutor);
                queueExecutor.runQueue();
                return futureReply.get();
            } finally {
                state.release();
            }
        }

        public FutureReply<O> send(final I request) throws RemotingException {
            state.requireHold(State.UP);
            try {
                final RequestIdentifier requestIdentifier;
                requestIdentifier = openRequest();
                final CoreOutboundRequest<I, O> outboundRequest = new CoreOutboundRequest<I, O>(CoreOutboundContext.this, requestIdentifier);
                requests.put(requestIdentifier, outboundRequest);
                // Request must be sent *after* the identifier is registered in the map
                sendRequest(requestIdentifier, request, endpoint.getExecutor());
                return outboundRequest.getFutureReply();
            } finally {
                state.release();
            }
        }

        public ConcurrentMap<Object, Object> getAttributes() {
            return contextMap;
        }

        public <T> T getService(final Class<T> serviceType) throws RemotingException {
            // todo interceptors
            return null;
        }

        public <T> boolean hasService(final Class<T> serviceType) {
            // todo interceptors
            return false;
        }
    }

    private static final CoreOutboundRequest[] empty = new CoreOutboundRequest[0];
}
