package njit.smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
//
// Wrapper class for DataPacket
public class PowerGrantPacket {
    // Number of power packets remaining is 4 bytes
    
    private final DatagramPacket packet;
    
    private static final int SEGMENT_SIZE = 12; // Size of data segment for each client in bytes
    private static final int TIMESTAMP_SIZE = 8;
    private static final int PKT_SIZE = 1472;   // Total packet size in bytes (over 1472 will fragment)
    static final int CLIENT_PORT = 1235;
    
    // Constructor (receive)
    public PowerGrantPacket() {
        byte[] buf = new byte[PKT_SIZE];
        packet = new DatagramPacket(buf, PKT_SIZE);
    }
    
    // Constructor (send)
    public PowerGrantPacket(InetAddress destAddr, Map<InetAddress, Queue<PowerRequest>> clientMap) throws UnknownHostException {
        // Check to make sure we have a valid number of clients
        // (non-negative and less than the maximum)
        final int numClients = clientMap.size();
        if (numClients < 0 || numClients * SEGMENT_SIZE  + TIMESTAMP_SIZE > PKT_SIZE) {
            throw new IllegalArgumentException(numClients + " is an invalid number of clients");
        }
        // Build the packet. Begin with a timestamp from the server, then add clients
	    ByteBuffer packetData = ByteBuffer.allocate(PKT_SIZE);
        packetData.putLong(System.currentTimeMillis());
        for (Map.Entry<InetAddress, Queue<PowerRequest>> client : clientMap.entrySet()) {
            // Copy client address into packet data buffer
            InetAddress clientAddr = client.getKey();
            packetData.put(clientAddr.getAddress());            
            // Copy power (in watts) and number of packets granted into packet data buffer (or copy zeros if no grant issued)
            if (client.getValue().isEmpty()) {
                packetData.putInt(0);
                packetData.putInt(0);
            } else {
                packetData.putInt(client.getValue().peek().getPowerGranted());
                packetData.putInt(client.getValue().peek().getPacketsRemaining());
            }
        }
        // Write four boundary bytes so we know where the real data stops
	    // TODO: need to check for size limit here
        for (int i = 0; i < 4; i++) {
            packetData.put((byte) 0xFF);
        }
        // Fill the rest of the packet with alternating 0's and 1's
//        while (packetData.hasRemaining()) {
//            packetData.put((byte) 0x55);   // ASCII 'U', also binary '01010101'
//        }
        packet = new DatagramPacket(packetData.array(), PKT_SIZE, destAddr, CLIENT_PORT);
    }
    
    public DatagramPacket getPacket() {
        return packet;
    }
    
    public byte[] getData() {
        return packet.getData();
    }

}
