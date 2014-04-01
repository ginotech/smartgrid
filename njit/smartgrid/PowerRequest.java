package njit.smartgrid;

import java.net.InetAddress;

public class PowerRequest {

    private InetAddress myAddr;
    private int powerRequested;
    private int powerGranted;

    public PowerRequest(InetAddress myAddr, int powerRequested) {
        this.myAddr = myAddr;
        this.powerRequested = powerRequested;
        this.powerGranted = 0;
    }

    public InetAddress getAddress() {
        return myAddr;
    }

    public int getPowerRequested() {
        return powerRequested;
    }

    public int getPowerGranted() {
        return powerGranted;
    }

    public void setPowerRequested(int newPower) {
        this.powerRequested = newPower;
    }

    public void setPowerGranted(int newPower) {
        this.powerGranted = newPower;
    }

    public void decrementPowerGranted() { this.powerGranted--; }

}
