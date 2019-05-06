package COS518.GroupZero.ChainReplication;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ChainClient {
    private static final Logger logger = Logger.getLogger(ChainClient.class.getName());

    private final ManagedChannel headChannel;
    private final ChainNodeGrpc.ChainNodeBlockingStub headBlockingStub;
    // private final ChainNodeGrpc.ChainNodeStub asyncStub;

    private final ManagedChannel tailChannel;
    private final ChainNodeGrpc.ChainNodeBlockingStub tailBlockingStub;
    // private final ChainNodeGrpc.ChainNodeStub asyncStub;

    public ChainClient(String headHost, int headPort, String tailHost, int tailPort) {
        headChannel = ManagedChannelBuilder.forAddress(headHost, headPort).usePlaintext().build();
        headBlockingStub = ChainNodeGrpc.newBlockingStub(headChannel);
        // asyncStub = ChainNodeGrpc.newStub(headChannel);

        tailChannel = ManagedChannelBuilder.forAddress(tailHost, tailPort).usePlaintext().build();
        tailBlockingStub = ChainNodeGrpc.newBlockingStub(tailChannel);
        // asyncStub = ChainNodeGrpc.newStub(headChannel);
    }

    public void shutdown() throws InterruptedException {
        headChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        tailChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Put a string into the configured chain.
     *
     * @param key key at which to place the string
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

    public static void main(String[] args) throws InterruptedException {
        ChainClient client = new ChainClient("localhost", 8980, "localhost", 8982);

        String putOne = "hello from client";
        String putTwo = "hello again from client";

        client.putString(1, putOne);
        client.putString(1, putTwo);

        String gotOne = client.getString(1);
        String gotTwo = client.getString(2);

        boolean error = false;

        if (gotTwo != null) {
            System.err.println("empty key did not return null, got: " + gotTwo);
            error = true;
        }

        if (!gotOne.equals(putTwo)) {
            System.err.println("key meant to contain: " + putTwo);
            System.err.println("key actually contained: " + gotOne);
            error = true;
        }

        client.shutdown();

        if (error) {
            System.err.println("ERROR");
        } else {
            System.out.println("SUCCESS");
        }
    }
}
