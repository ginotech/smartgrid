package smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
//
// Wrapper class for DataPacket
public class PowerPacket{
    // Client address is 1 byte
    // Number of power packets remaining is 4 bytes
    
    private final DatagramPacket packet;
    
    private static final int addrSize = 4;      // Client address size in bytes
    private static final int authSize = 4;      // Client power auth size in bytes. (MUST BE 4)
                                                // (4 bytes here gives a max value of 2^31 - 1 since 'int' is signed)
    private static final int packetSize = 1472; // Packet size in bytes (over 1472 will fragment)
    private static final int portNum = 1234;    // Port number
    
    // Constructor (receive)
    public PowerPacket() {
        byte[] buf = new byte[packetSize];
        packet = new DatagramPacket(buf, packetSize);
    }
    
    // Constructor (send)
    public PowerPacket(InetAddress destAddr, Map<InetAddress, Integer> clientAuthMap) throws UnknownHostException {
        // Check to make sure we have a valid number of clients
        // (non-negative and less than the maximum)
        final int numClients = clientAuthMap.size();
        if (numClients < 0 || numClients * addrSize * authSize > packetSize) {
            throw new IllegalArgumentException(numClients + " is an invalid number of clients");
        }
        // Build the packet. Begin with a start byte of 0xFF
		ByteBuffer packetData = ByteBuffer.allocate(packetSize);
        packetData.put((byte) 0xFF);
        for (Map.Entry<InetAddress, Integer> entry : clientAuthMap.entrySet()) {
            // Copy client address to packet data buffer
            InetAddress clientAddr = entry.getKey();
			packetData.put(clientAddr.getAddress());            
            // Copy authorization value to packet data buffer
			packetData.putInt(entry.getValue());
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
        packet = new DatagramPacket(packetData.array(), packetSize, destAddr, portNum);
    }
    
    public DatagramPacket getPacket() {
        return packet;
    }
    
    public byte[] getData() {
        return packet.getData();
    }
    
    public int getLength() {
        return packetSize;
    }
    
    public int getPort() {
        return portNum;
    }
}
