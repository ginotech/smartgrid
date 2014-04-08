package njit.smartgrid;

public class PowerRequest {

    static final int POWER_HIGH = 60;
    static final int POWER_LOW = 40;
    static final int POWER_BOTH = POWER_HIGH + POWER_LOW;

    private int durationRequested;
    private int durationGranted;
    private int powerRequested;
    private int powerGranted;

    public PowerRequest(int durationRequested, int powerRequested) {
        this.durationRequested = durationRequested;
        this.durationGranted = 0;
        this.powerRequested = powerRequested;
        this.powerGranted = 0;
    }

    public int getPowerRequested() { return powerRequested; }

    public int getPowerGranted() { return powerGranted; }

    public int getDurationRequested() { return durationRequested; }

    public int getDurationGranted() {
        return durationGranted;
    }

    public void setPowerRequested(int powerRequested) { this.powerRequested = powerRequested; }

    public void setPowerGranted(int powerGranted) { this.powerGranted = powerGranted; }

    public void setDurationRequested(int durationRequested) { this.durationRequested = durationRequested; }

    public void setDurationGranted(int durationGranted) {
        this.durationGranted = durationGranted;
    }

    public void decrementDurationGranted() { this.durationGranted--; }

}
