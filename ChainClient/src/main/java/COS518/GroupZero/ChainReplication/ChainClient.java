package COS518.GroupZero.ChainReplication;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ChainClient {
    private static final Logger logger = Logger.getLogger(ChainClient.class.getName());

    private final ManagedChannel channel;
    private final ChainNodeGrpc.ChainNodeBlockingStub blockingStub;
    private final ChainNodeGrpc.ChainNodeStub asyncStub;

    public ChainClient(String host, int port) {
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        blockingStub = ChainNodeGrpc.newBlockingStub(channel);
        asyncStub = ChainNodeGrpc.newStub(channel);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public void putString(int key, String string) {
        logger.info("at key " + key + " putting string: " + string);

        CRKey objectKey = CRKey.newBuilder().setKey(key).build();
        CRObject object = CRObject.newBuilder().setData(ByteString.copyFrom(string, Charset.defaultCharset())).build();
        CRPut message = CRPut.newBuilder().setKey(objectKey).setObject(object).build();

        CRObjectResponse response = blockingStub.putObject(message);
        if (response.getPresent()) {
            logger.info("string replaced: " + response.getObject().getData().toString(Charset.defaultCharset()));
        } else {
            logger.info("no previous string at location");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        ChainClient client = new ChainClient("localhost", 8980);
        client.putString(1, "hello from client");
        client.putString(1, "hello again from client");

        client.shutdown();
    }
}
