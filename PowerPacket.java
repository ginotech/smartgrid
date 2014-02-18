package smartgrid;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

// Wrapper class for DataPacket
public class PowerPacket{
    // Client address is 1 byte
    // Number of power packets remaining is 4 bytes
    
    private final DatagramPacket packet;
    
    private static final int addrSize = 1;      // Client address size in bytes (MUST BE 1)
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
    public PowerPacket(String addrString, int[] clientAuthArray) throws UnknownHostException {
        
        // Check to make sure we have a valid number of clients
        // (non-negative and less than the maximum)
        final int numClients = clientAuthArray.length;
        if (numClients < 0 || numClients * addrSize * authSize > packetSize) {
            throw new IllegalArgumentException(numClients + " is an invalid number of clients");
        }
        // Make sure we haven't changed the default sizes without rewriting the code to handle it!
        if (addrSize != 1 || authSize != 4) {
            throw new IllegalArgumentException("Address and auth sizes cannot be changed!");
        }
        
        byte[] buf = new byte[packetSize];
        InetAddress destAddr = InetAddress.getByName(addrString);
// TODO: Build entire meaningful packet section with a ByteBuffer
//       and then copy it to buf or something to save work
        // Build the packet. Begin with a start byte of 0xFF
        buf[0] = (byte) 0xFF;
        int count = 1;
        for (int clientAddr = 0; clientAddr < numClients; clientAddr++) {
// TODO: Allow variable size address and auth fields
//            if (addrSize > 1) {
//                ByteBuffer b = ByteBuffer.allocate(addrSize);
//                b.putInt(i);
//            } else {
//                
//            }
            // Copy client address to packet data buffer
            buf[count] = (byte) clientAddr; // numClients must be <= 255
            count += addrSize;
            
            // Copy authorization value to packet data buffer
            ByteBuffer authBuffer = ByteBuffer.allocate(authSize);
            authBuffer.putInt(clientAuthArray[clientAddr]);
            System.arraycopy(authBuffer.array(), 0, buf, count, authSize);
            count += authSize;
        }
        // Write a boundary byte so we know where the real data stops
        buf[count++] = (byte) 0xFF;
        // Fill the rest of the packet with alternating 0's and 1's
        while (count < packetSize) {
            buf[count++] = (byte) 0x55;   // ASCII 'U', also binary '01010101'
        }
        
        packet = new DatagramPacket(buf, packetSize, destAddr, portNum);
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
