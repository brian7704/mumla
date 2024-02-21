package se.lublin.mumla.location;

import java.util.ArrayList;

public class WebsocketMessage {
    private ArrayList<Position> positions;
    private ArrayList<Device> devices;

    public ArrayList<Position> getPositions() {
        return positions;
    }

    public void setPositions(ArrayList<Position> positions) {
        this.positions = positions;
    }

    public ArrayList<Device> getDevices() {
        return devices;
    }

    public void setDevices(ArrayList<Device> devices) {
        this.devices = devices;
    }
}
