package COS518.GroupZero.ChainReplication;

import COS518.GroupZero.ChainReplication.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ChainNodeServer {

    private static final Logger logger = Logger.getLogger(ChainNodeServer.class.getName());

    private final int port;
    private final Server rpcServer;

    // Private booleans defining the status of a chain node, whether it is a tail
    // or head node or not...
    private boolean isTail;
    private boolean isHead;

    // various interfaces for remote nodes
    private RemoteNodeRPC headNode;
    private RemoteNodeRPC successorNode;
    private RemoteNodeRPC tailNode;

    public ChainNodeServer(int port) {
        this.port = port;
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

    public static void main(String[] args) throws Exception {
        ChainNodeServer server = new ChainNodeServer(8980);
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
            if (isHead) {
                responseObserver.onNext(doInsert(request.getKey(), request.getObject()));

                // TODO: completely unclear if this is right.
                propagateWrite(request, responseObserver);

                responseObserver.onCompleted();

            } else {

                // Referring the request to the head node
                responseObserver.onNext(headNode.blockingPropagateWrite(request));
                responseObserver.onCompleted();

            }
        }

        @Override
        public void getObject(CRKey request, StreamObserver<CRObjectResponse> responseObserver) {
            if (isTail) {
                responseObserver.onNext(doRetrieve(request));
                responseObserver.onCompleted();
            } else {
                // orwarding the request to the tail node
                responseObserver.onNext(tailNode.blockingPropagateRead(request));
                responseObserver.onCompleted();
            }
        }

        @Override
        public void propagateWrite(CRPut request, StreamObserver<CRObjectResponse> responseObserver) {
            // TODO: write to local storage then propagate to successor. also unclear

            // Writing the key, value pair to local storage
            int objectKey = request.getKey().getKey();
            CRObject object = request.getObject();

            CRObject oldObject = tempObjectStore.put(objectKey, object);

            // set the response to present == false if previous was null, otherwise return previous value
            CRObjectResponse objectResponse;

            if (oldObject == null) {
                objectResponse = CRObjectResponse.newBuilder().setPresent(false).build();
            } else {
                objectResponse = CRObjectResponse.newBuilder().setPresent(true).setObject(oldObject).build();
            }

            if (!isTail && successorNode != null) {
                responseObserver.onNext(successorNode.blockingPropagateWrite(request));
                responseObserver.onCompleted();
            }

        }

        @Override
        public void updateHeadNode(CRNodeID nodeID, StreamObserver<UpdateStatus> responseObserver) {

            RemoteNodeRPC newHead;
            boolean success;

            try {
                newHead = new RemoteNodeRPC(nodeID);
                success = true;

                headNode.close();
                headNode = newHead;

            } catch (UnknownHostException e) {
                success = false;
            }

            UpdateStatus response = UpdateStatus.newBuilder().setSuccess(success).build();
            responseObserver.onNext(response);

            responseObserver.onCompleted();
        }

        @Override
        public void updateSuccessor(CRNodeID nodeID, StreamObserver<UpdateStatus> responseObserver) {
            RemoteNodeRPC newSuccessor;
            boolean success;

            try {
                // try to create the new remote node
                newSuccessor = new RemoteNodeRPC(nodeID);
                success = true;

                // if successful, close the old node and replace it
                successorNode.close();
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
        public void updateTailNode(CRNodeID nodeID, StreamObserver<UpdateStatus> responseObserver) {

            RemoteNodeRPC newTail;
            boolean success;

            try {
                newTail = new RemoteNodeRPC(nodeID);
                success = true;

                tailNode.close();
                tailNode = newTail;

            } catch (UnknownHostException e) {
                success = false;
            }

            UpdateStatus response = UpdateStatus.newBuilder().setSuccess(success).build();
            responseObserver.onNext(response);

            responseObserver.onCompleted();
        }

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
