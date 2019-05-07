package COS518.GroupZero.ChainReplication;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

/**
 * This class represents a connection to a remote node for the purposes of interfacing with its RPCs. Both the master
 * service and the chain nodes will use this to communicate with the nodes in a chain.
 */
public class RemoteNodeRPC {

    private final String host;
    private final int ip;
    private final int port;

    private final ManagedChannel channel;
    private final ChainNodeGrpc.ChainNodeBlockingStub blockingStub;
    private final ChainNodeGrpc.ChainNodeStub asyncStub;

    /**
     * Constructor. Configure the RPC stubs for a remote node. Fails on invalid parameters.
     *
     * @param nodeID identifier object for the remote node
     * @throws UnknownHostException when the ip address cannot be converted to a valid address
     */
    public RemoteNodeRPC(CRNodeID nodeID) throws UnknownHostException {
        this(intToIP(nodeID.getAddress()), nodeID.getPort());
    }

    /**
     * Constructor. Configure the RPC stubs for a remote node by directly entering host and port.
     *
     * @param host IPv4 address of remote node
     * @param port tcp port of remote node
     */
    public RemoteNodeRPC(String host, int port) throws UnknownHostException {
        this.ip = stringToIP(host);
        this.host = host;
        this.port = port;

        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        blockingStub = ChainNodeGrpc.newBlockingStub(channel);
        asyncStub = ChainNodeGrpc.newStub(channel);
    }

    /**
     * Blocking RPC to propagate a write down the chain to this remote node. The caller of the method should be
     * responsible for handling the response.
     *
     * @param request put request containing key and object
     */
    public CRObjectResponse blockingPropagateWrite(CRPut request) {
        return blockingStub.propagateWrite(request);
    }

    /**
     * Blocking RPC to update the node's head node configuration.
     *
     * @param host ipv4 address of the new head node
     * @param port tcp port of the new head node
     * @return true if the update occurred successfully
     * @throws UnknownHostException if the host cannot be converted properly to an integer
     */
    public boolean blockingUpdateHeadNode(String host, int port) throws UnknownHostException {
        // package the host ip address to an integer
        int addressInt = stringToIP(host);
        // create the request object with the correct ip and port
        CRNodeID nodeID = CRNodeID.newBuilder().setAddress(addressInt).setPort(port).build();

        // sent the request, wait for the response, and return the status
        UpdateStatus response = blockingStub.updateHeadNode(nodeID);
        return response.getSuccess();
    }

    /**
     * Blocking RPC to update the node's successor configuration.
     *
     * @param host ipv4 address of the new successor
     * @param port tcp port of the new successor
     * @return true if the update occurred successfully
     * @throws UnknownHostException if the host cannot be converted properly to an integer
     */
    public boolean blockingUpdateSuccessor(String host, int port) throws UnknownHostException {
        // package the host ip address to an integer
        int addressInt = stringToIP(host);
        // create the request object with the correct ip and port
        CRNodeID nodeID = CRNodeID.newBuilder().setAddress(addressInt).setPort(port).build();

        // sent the request, wait for the response, and return the status
        UpdateStatus response = blockingStub.updateSuccessor(nodeID);
        return response.getSuccess();
    }

    /**
     * Blocking RPC to update the node's tail node configuration.
     *
     * @param host ipv4 address of the new tail node
     * @param port tcp port of the new tail node
     * @return true if the update occurred successfully
     * @throws UnknownHostException if the host cannot be converted properly to an integer
     */
    public boolean blockingUpdateTailNode(String host, int port) throws UnknownHostException {
        // package the host ip address to an integer
        int addressInt = stringToIP(host);
        // create the request object with the correct ip and port
        CRNodeID nodeID = CRNodeID.newBuilder().setAddress(addressInt).setPort(port).build();

        // sent the request, wait for the response, and return the status
        UpdateStatus response = blockingStub.updateTailNode(nodeID);
        return response.getSuccess();
    }

    public CRNodeID getID() {
        return CRNodeID.newBuilder().setAddress(ip).setPort(port).build();
    }

    /**
     * Cleanup the channel when it is no longer needed.
     */
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.err.println("timeout on close for RemoteNode interface: " + host + ":" + port);
        }
    }

    /**
     * Convert an integer value IP address into the string representation of the IP address.
     *
     * @param intAddress integer representation of address
     * @return string representation of address
     * @throws UnknownHostException if the address is malformed or inaccessible
     */
    public static String intToIP(int intAddress) throws UnknownHostException {
        byte[] addressBytes = BigInteger.valueOf(intAddress).toByteArray();
        return InetAddress.getByAddress(addressBytes).getHostAddress();
    }

    /**
     * Convert an IP address string to an integer representation.
     *
     * @param ip ip address string
     * @return integer value of ip address
     * @throws UnknownHostException if the address is malformed or inaccessible
     */
    public static int stringToIP(String ip) throws UnknownHostException {
        byte[] addressBytes = InetAddress.getByName(ip).getAddress();
        return (new BigInteger(addressBytes)).intValue();
    }
}
