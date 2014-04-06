package njit.smartgrid;

import java.net.InetAddress;

public class PowerRequest {

    static final int HIGH_POWER_WATTS = 60;
    static final int LOW_POWER_WATTS = 40;

    private int durationRequested;
    private int durationGranted;
    private int powerRequested;
    private int powerGranted;

    public PowerRequest(int durationRequested, int powerRequested) {
        this.durationRequested = durationRequested;
        this.durationGranted = 0;
        if (powerRequested >= 60) {
            this.powerRequested = HIGH_POWER_WATTS;
        } else {
            this.powerRequested = LOW_POWER_WATTS;
        }
        this.powerGranted = 0;
    }

    public int getPowerRequested() { return powerRequested; }

    public int getPowerGranted() { return powerGranted; }

    public int getDurationRequested() { return durationRequested; }

    public int getDurationGranted() {
        return durationGranted;
    }

    public void setPowerGranted(int powerGranted) { this.powerGranted = powerGranted; }

    public void setDurationRequested(int durationRequested) { this.durationRequested = durationRequested; }

    public void setDurationGranted(int durationGranted) {
        this.durationGranted = durationGranted;
    }

    public void decrementDurationGranted() { this.durationGranted--; }

}
