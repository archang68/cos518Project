package COS518.GroupZero.ChainReplication;

import COS518.GroupZero.ChainReplication.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ChainNodeServer {

    private static final Logger logger = Logger.getLogger(ChainNodeServer.class.getName());

    private final int port;
    private final Server rpcServer;

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
            responseObserver.onNext(doInsert(request.getKey(), request.getObject()));
            responseObserver.onCompleted();
        }

        @Override
        public void getObject(CRKey request, StreamObserver<CRObjectResponse> responseObserver) {
            responseObserver.onNext(doRetrieve(request));
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
