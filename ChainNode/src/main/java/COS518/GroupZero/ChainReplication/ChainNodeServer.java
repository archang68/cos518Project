package COS518.GroupZero.ChainReplication;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class ChainNodeServer {

    private static final Logger logger = Logger.getLogger(ChainNodeServer.class.getName());

    private final int port;
    private final Server rpcServer;

    // Private booleans defining the status of a chain node, whether it is a tail
    // or head node or not...
    private boolean isHead;
    private boolean isTail;

    // various interfaces for remote nodes
    private RemoteNodeRPC successorNode;

    public ChainNodeServer(int port, boolean setHead, boolean setTail,
                           String successor, int successorPort) throws UnknownHostException {

        this.port = port;
        this.isHead = setHead;
        this.isTail = setTail;

        if (!isTail) {
            successorNode = new RemoteNodeRPC(successor, successorPort);
        }

        rpcServer = ServerBuilder.forPort(port).addService(new ChainNodeService()).build();
    }

    public void start() throws IOException {
        rpcServer.start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may has been reset by its JVM shutdown hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            ChainNodeServer.this.stop();
            System.err.println("*** server shut down");
        }));
    }

    public void stop() {
        if (rpcServer != null) {
            rpcServer.shutdown();
        }
    }

    private void blockUntilShutdown() throws InterruptedException {
        if (rpcServer != null) {
            rpcServer.awaitTermination();
        }
    }

    /**
     * Start this server node.
     *
     * @param args port role [head] [successor] [tail]
     * @throws Exception on arbitrary failure to start
     */
    public static void main(String[] args) throws Exception {
        // get port from command arguments
        int initPort = Integer.parseInt(args[0]);
        if (initPort < 1024 || initPort > 65535) {
            System.err.println("Port must be within [1024, 65535].");
            System.exit(-1);
        }

        // get role from command arguments
        String role = args[1];
        boolean initHead = false;
        boolean initTail = false;
        if (role.equalsIgnoreCase("head")) {
            initHead = true;
        } else if (role.equalsIgnoreCase("tail")) {
            initTail = true;
        } else if (!role.equalsIgnoreCase("middle")) {
            System.err.println("Role must be one of {head, tail, middle}.");
            System.exit(-1);
        }

        // create server based on role
        String[] successor;
        if (initHead) {
            System.out.println("Starting Head Node");
            successor = args[2].split(":");
        } else if (initTail) {
            System.out.println("Starting Tail Node");
            successor = new String[2];
            successor[0] = null;
            successor[1] = "-1";
        } else {
            System.out.println("Starting Center Node");
            successor = args[2].split(":");
        }
        ChainNodeServer server = new ChainNodeServer(initPort, initHead, initTail,
                successor[0], Integer.parseInt(successor[1]));

        server.start();
        server.blockUntilShutdown();
    }

    private class ChainNodeService extends ChainNodeGrpc.ChainNodeImplBase {

        /**
         * Temporary in-memory object store. Currently implemented with ConcurrentHashMap, which uses read-write locking
         * for per-key write mutual exclusion.
         */
        private ConcurrentHashMap<Integer, CRObject> objectStore = new ConcurrentHashMap<>();

        /**
         * This is a special in-memory object store that is used for tracking writes that need to be
         * propagated across the nodes
         */
        private ConcurrentHashMap<Integer, CRObject> tempObjectStore = new ConcurrentHashMap<>();

        @Override
        public void putObject(CRPut request, StreamObserver<CRObjectResponse> responseObserver) {
            logger.info("received put request for key " + request.getKey());
            if (isHead) {
                // insert object into local store
                CRObjectResponse inserted = doInsert(request.getKey(), request.getObject());

                // propagate object to chain (unless head is also tail)
                if (!isTail)
                    inserted = successorNode.blockingPropagateWrite(request);

                // return the object to requester after propagation
                responseObserver.onNext(inserted);
                responseObserver.onCompleted();

            } else {
                // TODO: deny or forward?
                System.err.println("non-head node received putObject request");
                System.exit(-1);

                // Referring the request to the head node
                // responseObserver.onNext(headNode.blockingPropagateWrite(request));
                // responseObserver.onCompleted();

            }
        }

        @Override
        public void getObject(CRKey request, StreamObserver<CRObjectResponse> responseObserver) {
            logger.info("received get request for key " + request.getKey());
            if (isTail) {
                responseObserver.onNext(doRetrieve(request));
                responseObserver.onCompleted();
            } else {
                // TODO: deny or forward?
                System.err.println("non-tail node received getObject request");
                System.exit(-1);

                // forwarding the request to the tail node
                // responseObserver.onNext(tailNode.blockingPropagateRead(request));
                // responseObserver.onCompleted();
            }
        }

        @Override
        public void propagateWrite(CRPut request, StreamObserver<CRObjectResponse> responseObserver) {
            logger.info("received write-propagate request for key " + request.getKey());

            // writing the key, value pair to local storage
            CRObjectResponse inserted = doInsert(request.getKey(), request.getObject());

            if (!isTail) {
                // if not the tail node, propagate to successor
                CRObjectResponse propagated = successorNode.blockingPropagateWrite(request);
                responseObserver.onNext(propagated);
            } else {
                // if tail, just respond with inserted (return up the chain)
                responseObserver.onNext(inserted);
            }

            // completed operation
            responseObserver.onCompleted();
        }

        @Override
        public void updateHeadNode(UpdateRoleMessage update, StreamObserver<UpdateStatus> responseObserver) {
            logger.info("I am new Head Node");
            isHead = true;

            // send the response containing the success status (true or false)
            UpdateStatus response = UpdateStatus.newBuilder().setSuccess(true).build();
            responseObserver.onNext(response);

            // complete the RPC
            responseObserver.onCompleted();
        }

        @Override
        public void updateSuccessor(CRNodeID nodeID, StreamObserver<UpdateStatus> responseObserver) {
            RemoteNodeRPC newSuccessor;
            boolean success;

            try {
                logger.info("updating successor to " +
                        RemoteNodeRPC.intToIP(nodeID.getAddress()) + ":" + nodeID.getPort());

                // try to create the new remote node
                newSuccessor = new RemoteNodeRPC(nodeID);
                success = true;

                // if successful, close the old node and replace it
                if (successorNode != null) {
                    successorNode.close();
                }
                successorNode = newSuccessor;

            } catch (UnknownHostException e) {
                // keep old node if unsuccessful
                success = false;
            }

            // send the response containing the success status (true or false)
            UpdateStatus response = UpdateStatus.newBuilder().setSuccess(success).build();
            responseObserver.onNext(response);

            // complete the RPC
            responseObserver.onCompleted();
        }

        @Override
        public void updateTailNode(UpdateRoleMessage update, StreamObserver<UpdateStatus> responseObserver) {
            logger.info("I am new tail node.");

            isTail = true;

            // send the response containing the success status (true or false)
            UpdateStatus response = UpdateStatus.newBuilder().setSuccess(true).build();
            responseObserver.onNext(response);

            // complete the RPC
            responseObserver.onCompleted();
        }

        @Override
        public void checkHeartbeat(CheckAliveMessage request, StreamObserver<Heartbeat> responseObserver) {
            Heartbeat response = Heartbeat.newBuilder().setAlive(true).build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        /**
         * Helper function to write a key and object to the local storage.
         *
         * @param key    insert object key
         * @param object insert object data
         * @return response with present bool and old object as appropriate
         */
        private CRObjectResponse doInsert(CRKey key, CRObject object) {
            // do the insert and retrieve the previous value
            int objectKey = key.getKey();
            CRObject oldObject = objectStore.put(objectKey, object);

            // set the response to present == false if previous was null, otherwise return previous value
            CRObjectResponse objectResponse;

            if (oldObject == null) {
                objectResponse = CRObjectResponse.newBuilder().setPresent(false).build();
            } else {
                objectResponse = CRObjectResponse.newBuilder().setPresent(true).setObject(oldObject).build();
            }

            return objectResponse;
        }

        /**
         * Helper function to get an object present at the specified key.
         *
         * @param key identifies object to get
         * @return response with present bool and object as appropriate
         */
        private CRObjectResponse doRetrieve(CRKey key) {
            CRObject object = objectStore.get(key.getKey());

            // set the response to present == false if return was null, otherwise return value
            CRObjectResponse objectResponse;

            if (object == null) {
                objectResponse = CRObjectResponse.newBuilder().setPresent(false).build();
            } else {
                objectResponse = CRObjectResponse.newBuilder().setPresent(true).setObject(object).build();
            }

            return objectResponse;
        }
    }
}
