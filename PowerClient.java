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
		System.out.println("My address is " + myAddr);
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
                    i++;
                    if (clientAddr == myAddr) {
                        System.out.format("Buffer value: 0x%02X%02X%02X%02X\n", buf[i], buf[i+1], buf[i+2], buf[i+3]);
                        authBuffer = ByteBuffer.wrap(buf, i, 4);
                        System.out.println("Received auth for " + authBuffer.getInt());
                        break;
                    }
                    i += 4;
                    clientAddr = buf[i];
                }
            }
            
        } catch (UnknownHostException e) {
            
        } catch (SocketException e) {
            
        }
    }
    
}
