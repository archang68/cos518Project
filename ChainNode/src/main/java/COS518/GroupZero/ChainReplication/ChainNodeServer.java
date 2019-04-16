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

        @Override
        public void putObject(CRPut request, StreamObserver<CRObjectResponse> responseObserver) {
            if (isHead) {
                responseObserver.onNext(doInsert(request.getKey(), request.getObject()));

                // do we have to propagate the insert to the tail before completing/stating that
                // this is completed?
                responseObserver.onCompleted();

                // we need to do some kind of recursive thing here...b/c we need to send back
                // many onCompleted() back to the previous servers...argh...
                // so basically we have to call putObject?

                // Where to put this logic?

                // need to hold onto the object until we get the confirmation from the propagation


            } else {

                // add necessary logic for forwarding the request to the head node
                // should we just maintain the head node of the chain somewhere to make lives easier

            }
        }

        @Override
        public void getObject(CRKey request, StreamObserver<CRObjectResponse> responseObserver) {
            if (isTail) {
                responseObserver.onNext(doRetrieve(request));
                responseObserver.onCompleted();
            } else {
                // Add logic for forwarding the request to the tail node - if we can track
                // the tail + head node, don't need to walk down the successor chain
            }
        }

        @Override
        public void propagateWrite(CRPut request, StreamObserver<CRObjectResponse> responseObserver) {
            // TODO: write to local storage then propagate to successor

        }

        @Override
        public void updateHeadNode(CRNodeID nodeID, StreamObserver<UpdateStatus> responseObserver) {
            // TODO: same as updateSuccessor, but with the head node
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
            // TODO: same as updateSuccessor, but with the tail node
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
