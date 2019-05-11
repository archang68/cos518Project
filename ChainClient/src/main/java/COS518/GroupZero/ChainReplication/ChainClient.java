package COS518.GroupZero.ChainReplication;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.Random;

public class ChainClient {
    private static final Logger logger = Logger.getLogger(ChainClient.class.getName());

    private final ManagedChannel masterChannel;
    private final ChainMasterGrpc.ChainMasterBlockingStub masterBlockingStub;

    private ManagedChannel headChannel;
    private ChainNodeGrpc.ChainNodeBlockingStub headBlockingStub;
    // private final ChainNodeGrpc.ChainNodeStub asyncStub;

    private ManagedChannel tailChannel;
    private ChainNodeGrpc.ChainNodeBlockingStub tailBlockingStub;
    // private final ChainNodeGrpc.ChainNodeStub asyncStub;

    public ChainClient(String masterHost, int masterPort) throws UnknownHostException {
        masterChannel = ManagedChannelBuilder.forAddress(masterHost, masterPort).usePlaintext().build();
        masterBlockingStub = ChainMasterGrpc.newBlockingStub(masterChannel);

        updateHeadTail();
    }

    public void shutdown() throws InterruptedException {
        if (masterChannel != null)
            masterChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        if (headChannel != null)
            headChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        if (tailChannel != null)
            tailChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private void updateHeadTail() throws UnknownHostException {
        // get head node id
        CRNodeID headNode = masterBlockingStub.getHead(HeadRequest.getDefaultInstance());
        String headHost = RemoteNodeRPC.intToIP(headNode.getAddress());
        int headPort = headNode.getPort();

        headChannel = ManagedChannelBuilder.forAddress(headHost, headPort).usePlaintext().build();
        headBlockingStub = ChainNodeGrpc.newBlockingStub(headChannel);
        // asyncStub = ChainNodeGrpc.newStub(headChannel);

        // get tail node id
        CRNodeID tailNode = masterBlockingStub.getTail(TailRequest.getDefaultInstance());
        String tailHost = RemoteNodeRPC.intToIP(tailNode.getAddress());
        int tailPort = tailNode.getPort();

        tailChannel = ManagedChannelBuilder.forAddress(tailHost, tailPort).usePlaintext().build();
        tailBlockingStub = ChainNodeGrpc.newBlockingStub(tailChannel);
        // asyncStub = ChainNodeGrpc.newStub(headChannel);
    }

    /**
     * Put a string into the configured chain.
     *
     * @param key    key at which to place the string
     * @param string string to place
     */
    public void putString(int key, String string) {
        logger.info("at key " + key + " putting string: " + string);

        CRKey objectKey = CRKey.newBuilder().setKey(key).build();
        CRObject object = CRObject.newBuilder().setData(ByteString.copyFrom(string, Charset.defaultCharset())).build();
        CRPut message = CRPut.newBuilder().setKey(objectKey).setObject(object).build();

        CRObjectResponse response = headBlockingStub.putObject(message);
        if (response.getPresent()) {
            logger.info("string replaced: " + response.getObject().getData().toString(Charset.defaultCharset()));
        } else {
            logger.info("no previous string at location");
        }
    }

    /**
     * Get a string from the configured chain.
     *
     * @param key object key to fetch
     * @return string returned from chain or null if none present
     */
    public String getString(int key) {
        logger.info("getting string at key: " + key);

        CRKey objectKey = CRKey.newBuilder().setKey(key).build();
        CRObjectResponse response = tailBlockingStub.getObject(objectKey);
        if (response.getPresent()) {
            String stringObject = response.getObject().getData().toString(Charset.defaultCharset());
            logger.info("string returned: " + stringObject);
            return stringObject;
        } else {
            logger.info("no string returned");
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        ChainClient client = new ChainClient("localhost", 9000);

        // We expect two command line arguments to our client (we are utilizing this as a
        // test client)

        // java ChainClient [number of total requests to be made by the client] [percentage of reads]

        if (args.length != 3) {
            throw new IllegalArgumentException("Incorrect number of arguments.");
        }

        int numRequests = Integer.parseInt(args[0]);
        int percRead = Integer.parseInt(args[1]);
	    int seed = Integer.parseInt(args[2]);

        // The client will continue to send requests
        String dummyString = "value";

        // Random number generator to determine whether or not to send a read/write
        Random r = new Random(seed);

        int numReadsDone = 0;
        int numWritesDone = 0;

        int intendedReads = (numRequests * percRead)/100;
        int intendedWrites = (numRequests * (100 - percRead))/100;

        for (int i = 0; i < numRequests; ) {

            // Randomly interspersing reads and writes
            if (numReadsDone <= intendedReads && r.nextInt(100) < percRead) {
                client.putString(i, dummyString + i);
                numReadsDone++;
                i++;
            } else if (numWritesDone <= intendedWrites) {

                int potentialKey = r.nextInt(i);
                String retrieved = client.getString(potentialKey);

                numWritesDone++;
                i++;
            }

        }

//        String putOne = "hello from client";
//        String putTwo = "hello again from client";
//
//        client.putString(1, putOne);
//        client.putString(1, putTwo);
//
//        String gotOne = client.getString(1);
//        String gotTwo = client.getString(2);
//
//        boolean error = false;
//
//        if (gotTwo != null) {
//            System.err.println("empty key did not return null, got: " + gotTwo);
//            error = true;
//        }
//
//        if (!gotOne.equals(putTwo)) {
//            System.err.println("key meant to contain: " + putTwo);
//            System.err.println("key actually contained: " + gotOne);
//            error = true;
//        }

        client.shutdown();

//        if (error) {
//            System.err.println("ERROR");
//        } else {
//            System.out.println("SUCCESS");
//        }
    }
}
