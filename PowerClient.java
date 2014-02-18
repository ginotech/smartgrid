package smartgrid;
import smartgrid.PowerPacket;
import java.net.DatagramSocket;
import java.net.UnknownHostException;
import java.net.SocketException;
import java.io.IOException;
import java.nio.ByteBuffer;


public class PowerClient {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        int myAddr = Integer.parseInt(args[0]);
        try {
            DatagramSocket receiveSocket = new DatagramSocket(1234);
            while(true) {
                PowerPacket packet = new PowerPacket();
                receiveSocket.receive(packet.getPacket());
                byte[] buf = new byte[packet.getLength()];
                buf = packet.getData();
                int clientAddr = buf[1];
                int i = 1;
                ByteBuffer authBuffer = ByteBuffer.allocate(4);
                while (clientAddr < 0xFF) {
                    if (clientAddr == myAddr) {
                        i++;
                        authBuffer.wrap(buf, i, 4);
                        System.out.println("Received auth for " + authBuffer.getInt());
                    }
                }
                
            }
            
        } catch (UnknownHostException e) {
            
        } catch (SocketException e) {
            
        }
    }
    
}
