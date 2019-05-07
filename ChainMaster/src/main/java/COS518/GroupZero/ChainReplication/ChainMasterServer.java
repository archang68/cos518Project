package COS518.GroupZero.ChainReplication;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ChainMasterServer {
    private static final Logger logger = Logger.getLogger(ChainMasterServer.class.getName());

    private final int port;
    private final Server rpcServer;

    private List<RemoteNodeRPC> chainNodes = new ArrayList<>();
    private ReadWriteLock nodeListLock = new ReentrantReadWriteLock();

    public ChainMasterServer(int port, List<CRNodeID> nodesList) throws UnknownHostException {
        this.port = port;
        rpcServer = ServerBuilder.forPort(port).addService(new ChainMasterService()).build();

        for (CRNodeID node : nodesList) {
            chainNodes.add(new RemoteNodeRPC(node));
        }
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

        Lock writerLock = nodeListLock.writeLock();
        writerLock.lock();

        for (RemoteNodeRPC node : chainNodes) {
            node.close();
        }

        writerLock.unlock();
    }

    private void blockUntilShutdown() throws InterruptedException {
        if (rpcServer != null) {
            rpcServer.awaitTermination();
        }
    }

    public static void main(String[] args) throws Exception {

    }

    private static List<CRNodeID> readConfig(Path configFile) throws IOException {
        List<CRNodeID> nodes = new ArrayList<>();

        try (Stream<String> lines = Files.lines(configFile)) {
            List<String> linesList = lines.filter(line -> !line.startsWith("//")).collect(Collectors.toList());

            for (String line : linesList) {
                CRNodeID currentNode;
                String[] splitLine = line.split(":");

                int address = RemoteNodeRPC.stringToIP(splitLine[0]);
                int port = Integer.parseInt(splitLine[1]);

                currentNode = CRNodeID.newBuilder().setAddress(address).setPort(port).build();

                nodes.add(currentNode);
            }

        } catch (IOException ex) {
            System.err.println("failed to read configuration file");
            throw ex;
        }

        return nodes;
    }

    private class ChainMasterService extends ChainMasterGrpc.ChainMasterImplBase {

        @Override
        public void getHead(HeadRequest request, StreamObserver<CRNodeID> responseObserver) {
            Lock readerLock = nodeListLock.readLock();
            readerLock.lock();

            try {
                responseObserver.onNext(chainNodes.get(0).getID());
            } finally {
                readerLock.unlock();
            }

            responseObserver.onCompleted();
        }

        @Override
        public void getTail(TailRequest request, StreamObserver<CRNodeID> responseObserver) {
            Lock readerLock = nodeListLock.readLock();
            readerLock.lock();

            try {
                responseObserver.onNext(chainNodes.get(chainNodes.size() - 1).getID());
            } finally {
                readerLock.unlock();
            }

            responseObserver.onCompleted();
        }
    }

}
