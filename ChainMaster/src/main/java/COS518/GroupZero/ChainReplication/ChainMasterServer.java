package COS518.GroupZero.ChainReplication;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.logging.Logger;

public class ChainMasterServer {
    private static final Logger logger = Logger.getLogger(ChainMasterServer.class.getName());

    private final int port;
    private final Server rpcServer;

    public ChainMasterServer(int port) {
        this.port = port;
        rpcServer = ServerBuilder.forPort(port).addService(new ChainMasterService()).build();
    }

    public void start() throws IOException {
        rpcServer.start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may has been reset by its JVM shutdown hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            ChainMasterServer.this.stop();
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

    }

    private class ChainMasterService extends ChainMasterGrpc.ChainMasterImplBase {

    }

}
