package njit.smartgrid;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;

public class PowerLog {

    private static final String DELIMITER = ", ";

    private boolean isServer = false;
    private FileWriter fw = null;
    private BufferedWriter bw = null;

    // Constructor (server log)
    public PowerLog(boolean isServer) {
        this.isServer = isServer;
        Date date = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        String fileName;
        if (isServer) {
            fileName = "server_";
        }
        else {
            fileName = "client_";
        }
        fileName += dateFormat.format(date) + ".log";
        try {
            fw = new FileWriter(fileName);
            bw = new BufferedWriter(fw);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

    }

    public void logRequest(InetAddress clientAddress, double clientBatteryLevel, int powerRequested,
                           long clientTimestamp) {
        String logString = "REQ" + DELIMITER;
        Timestamp time = new Timestamp(System.currentTimeMillis());
        logString += time.toString() + DELIMITER;   // Local timestamp (server or client)
        if (isServer) {
            String clientRequestTime = new Timestamp(clientTimestamp).toString();
            logString += clientRequestTime + DELIMITER;  // Client timestamp (if server)
        }
        logString += clientAddress.getHostAddress() + DELIMITER;    // Client IP address
        logString += clientBatteryLevel + DELIMITER;                // Client battery level
        logString += powerRequested;

        try {
            bw.write(logString, 0, logString.length());
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void logGrant(InetAddress clientAddress, int powerGranted, long serverTimestamp) {
        String logString = "GRA" + DELIMITER;
        Timestamp time = new Timestamp(System.currentTimeMillis());
        logString += time.toString() + DELIMITER;   // Local timestamp (server or client)
        if (!isServer) {
            String serverGrantTime = new Timestamp(serverTimestamp).toString();
            logString += serverGrantTime + DELIMITER;   // Server timestamp (if client)
        }
        logString += clientAddress.getHostAddress() + DELIMITER;
        logString += powerGranted;
        try {
            bw.write(logString, 0, logString.length());
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
