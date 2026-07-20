package com.bwtest.console.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** A connected agent as the UI sees it. Backed by JavaFX properties so the
 *  agent list and capability badges update live. */
public class AgentModel {
    public final String id;
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty os = new SimpleStringProperty();
    private final StringProperty arch = new SimpleStringProperty();
    private final StringProperty dataAddr = new SimpleStringProperty();
    private final StringProperty status = new SimpleStringProperty("connected");
    private final BooleanProperty online = new SimpleBooleanProperty(true);
    public Capabilities caps = new Capabilities();

    public AgentModel(String id) {
        this.id = id;
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty osProperty() { return os; }
    public StringProperty archProperty() { return arch; }
    public StringProperty dataAddrProperty() { return dataAddr; }
    public StringProperty statusProperty() { return status; }
    public BooleanProperty onlineProperty() { return online; }

    public String getName() { return name.get(); }
    public void setName(String v) { name.set(v); }
    public String getOs() { return os.get(); }
    public void setOs(String v) { os.set(v); }
    public String getArch() { return arch.get(); }
    public void setArch(String v) { arch.set(v); }
    public String getDataAddr() { return dataAddr.get(); }
    public void setDataAddr(String v) { dataAddr.set(v); }
    public void setStatus(String v) { status.set(v); }
    public void setOnline(boolean v) { online.set(v); }

    /** e.g. "linux/x86_64" */
    public String platform() {
        return getOs() + "/" + getArch();
    }

    @Override
    public String toString() {
        return getName() + "  (" + platform() + ")";
    }
}
