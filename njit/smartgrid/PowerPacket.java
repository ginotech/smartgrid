package njit.smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
//
// Wrapper class for DataPacket
public class PowerPacket{
    // Number of power packets remaining is 4 bytes
    
    private final DatagramPacket packet;
    
    private static final int ADDR_SIZE = 4;      // Client address size in bytes (IPv4 address is 4 bytes)
    private static final int AUTH_SIZE = 4;      // Client power auth size in bytes. (MUST BE 4)
                                                // (4 bytes here gives a max value of 2^31 - 1 since 'int' is signed)
    private static final int PKT_SIZE = 1472; // Packet size in bytes (over 1472 will fragment)
    private static final int PORT = 1234;    // Port number
    
    // Constructor (receive)
    public PowerPacket() {
        byte[] buf = new byte[PKT_SIZE];
        packet = new DatagramPacket(buf, PKT_SIZE);
    }
    
    // Constructor (send)
    public PowerPacket(InetAddress destAddr, Queue<PowerRequest> clientsActive) throws UnknownHostException {
        // Check to make sure we have a valid number of clients
        // (non-negative and less than the maximum)
        final int numClients = clientsActive.size();
        if (numClients < 0 || numClients * ADDR_SIZE * AUTH_SIZE > PKT_SIZE) {
            throw new IllegalArgumentException(numClients + " is an invalid number of clients");
        }
        // Build the packet. Begin with a start byte of 0xFF
	ByteBuffer packetData = ByteBuffer.allocate(PKT_SIZE);
        packetData.put((byte) 0xFF);
        for (PowerRequest entry : clientsActive) {
            // Copy client address to packet data buffer
            InetAddress clientAddr = entry.getAddress();
            packetData.put(clientAddr.getAddress());            
            // Copy authorization value to packet data buffer
            packetData.putInt(entry.getPowerRequested());
        }
        // Write four boundary bytes so we know where the real data stops
	// TODO: need to check for size limit here
        for (int i = 0; i < 4; i++) {
            packetData.put((byte) 0xFF);
        }
        // Fill the rest of the packet with alternating 0's and 1's
        while (packetData.hasRemaining()) {
            packetData.put((byte) 0x55);   // ASCII 'U', also binary '01010101'
        }
        packet = new DatagramPacket(packetData.array(), PKT_SIZE, destAddr, PORT);
    }
    
    public DatagramPacket getPacket() {
        return packet;
    }
    
    public byte[] getData() {
        return packet.getData();
    }
    
    public int getLength() {
        return PKT_SIZE;
    }
    
    public int getPort() {
        return PORT;
    }
}
