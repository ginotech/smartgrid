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
    private static final boolean HUMAN_READABLE = true;

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

    public void logRequest(InetAddress clientAddress, int powerRequested, int durationRequested,
                           long clientTimestamp) {
        String logString = "REQ" + DELIMITER;
        if (HUMAN_READABLE) {
            Timestamp time = new Timestamp(System.currentTimeMillis());
            logString += time.toString() + DELIMITER;   // Local timestamp (server or client)
        } else {
            logString += System.currentTimeMillis() + DELIMITER;
        }
        if (isServer) {
            if (HUMAN_READABLE) {
                String clientRequestTime = new Timestamp(clientTimestamp).toString();
                logString += clientRequestTime + DELIMITER;  // Client timestamp (if server)
            } else {
                logString += clientTimestamp + DELIMITER;
            }

        }
        logString += clientAddress.getHostAddress() + DELIMITER;    // Client IP address
        logString += powerRequested + "W" + DELIMITER;              // Power requested
        logString += durationRequested + "s";                       // Duration requested

        try {
            bw.write(logString, 0, logString.length());
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void logGrant(InetAddress clientAddress, int powerGranted, int durationGranted, long serverTimestamp) {
        String logString = "GRA" + DELIMITER;
        if (HUMAN_READABLE) {
            Timestamp time = new Timestamp(System.currentTimeMillis());
            logString += time.toString() + DELIMITER;   // Local timestamp (server or client)
        } else {
            logString += System.currentTimeMillis() + DELIMITER;
        }
        if (!isServer) {
            if (HUMAN_READABLE) {
                String serverGrantTime = new Timestamp(serverTimestamp).toString();
                logString += serverGrantTime + DELIMITER;   // Server timestamp (if client)
            } else {
                logString += serverTimestamp + DELIMITER;
            }
        }
        logString += clientAddress.getHostAddress() + DELIMITER;
        logString += powerGranted + "W" + DELIMITER;
        logString += durationGranted;
        try {
            bw.write(logString, 0, logString.length());
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
