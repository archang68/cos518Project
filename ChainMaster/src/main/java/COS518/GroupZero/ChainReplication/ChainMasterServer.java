package COS518.GroupZero.ChainReplication;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
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

    private final TimerTask serverMonitor;
    private final Timer serverMonitorTimer = new Timer(true);
    private final static int MONITOR_INTERVAL_MS = 2000;

    private List<RemoteNodeRPC> chainNodes = new ArrayList<>();
    private ReadWriteLock nodeListLock = new ReentrantReadWriteLock();

    public ChainMasterServer(int port, List<CRNodeID> nodesList) throws UnknownHostException {
        this.port = port;
        rpcServer = ServerBuilder.forPort(port).addService(new ChainMasterService()).build();

        serverMonitor = new ChainMonitorTask();

        for (CRNodeID node : nodesList) {
            chainNodes.add(new RemoteNodeRPC(node));
        }
    }

    public void start() throws IOException {
        rpcServer.start();
        logger.info("Server started, listening on " + port);

        serverMonitorTimer.schedule(serverMonitor, MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS);
        logger.info("Timer started, interval at " + MONITOR_INTERVAL_MS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may has been reset by its JVM shutdown hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            ChainMasterServer.this.stop();
            System.err.println("*** server shut down");
        }));
    }

    public void stop() {
        // stop the rpc server
        if (rpcServer != null) {
            rpcServer.shutdown();
        }

        // stop the scheduled task for keeping track of nodes
        if (serverMonitor != null) {
            serverMonitor.cancel();
        }

        // gracefully exit the timer thread
        serverMonitorTimer.cancel();

        // close all connections to chain nodes
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
        List<CRNodeID> chainNodes = readConfig(Paths.get(args[1]));

        ChainMasterServer masterServer = new ChainMasterServer(Integer.parseInt(args[0]), chainNodes);
        masterServer.start();
        masterServer.blockUntilShutdown();
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

    private class ChainMonitorTask extends TimerTask {

        /**
         * Check heartbeat of each server in the chain and handle failures by re-organizing nodes.
         */
        @Override
        public void run() {
            nodeListLock.writeLock().lock();

            try {
                boolean changed = updateChainData();

                if (changed) {
                    sendUpdateMessages();
                }

            } catch (Exception ex) {
                logger.severe("Failure during internal state update.");
                throw ex;
            } finally {
                nodeListLock.writeLock().unlock();
            }

        }

        /**
         * Primary failure detection and recovery procedure.
         */
        private boolean updateChainData() {
            // find dead nodes
            List<Boolean> aliveNodes = checkNodes();

            boolean changed = false;

            // remove dead nodes from list
            for (int i = aliveNodes.size() - 1; i >= 0; i--) {
                if (!aliveNodes.get(i)) {
                    changed = true;
                    chainNodes.remove(i);
                    logger.warning("node dead: " + i);
                }
            }

            return changed;
        }

        /**
         * Notify the nodes of their updated configuration.
         */
        private void sendUpdateMessages() {
            for (int i = 0; i < chainNodes.size(); i++) {
                RemoteNodeRPC node = chainNodes.get(i);

                if (i == 0) {
                    // tell the new head its role
                    node.nonBlockingUpdateHeadNode();
                }

                if (i == chainNodes.size() - 1) {
                    // tell the new tail its role
                    node.nonBlockingUpdateTailNode();
                }

                // update the node's successor unless it is the tail
                if (i != chainNodes.size() - 1) {
                    node.nonBlockingUpdateSuccessor(chainNodes.get(i + 1).getID());
                }
            }
        }

        /**
         * Check each node of the chain for heartbeat and return list of status that correspond to each node's alive
         * or dead status (where true means alive).
         *
         * @return list of boolean alive statuses in order of chain
         */
        private List<Boolean> checkNodes() {
            // implemented using parallel stream (which may execute in parallel but will always consist ordering)
            return chainNodes.parallelStream().map(RemoteNodeRPC::checkHeartbeat).collect(Collectors.toList());
        }
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
