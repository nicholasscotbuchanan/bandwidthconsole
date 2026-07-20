package com.bwtest.console.ui;

import com.bwtest.console.model.AgentModel;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/** The live roster of connected test agents, each with its capability badges. */
public class AgentsPane extends VBox {

    /** Fixed cell height: card content plus the 8px gap painted below each card. */
    private static final double CELL_HEIGHT = 96;

    public AgentsPane(ObservableList<AgentModel> agents) {
        getStyleClass().add("panel");
        setSpacing(8);
        setPrefWidth(360);

        Label title = new Label("TEST AGENTS");
        title.getStyleClass().add("panel-title");

        ListView<AgentModel> list = new ListView<>(agents);
        list.getStyleClass().add("agent-list");
        list.setFocusTraversable(false);
        list.setFixedCellSize(CELL_HEIGHT);
        // Size to the roster so no agent is ever clipped mid-card; the left
        // column's outer ScrollPane absorbs any overflow.
        list.prefHeightProperty().bind(Bindings.max(80,
                Bindings.size(agents).multiply(CELL_HEIGHT).add(2)));
        list.setPlaceholder(hintLabel("No agents connected.\nStart bwagent pointing at this console."));
        list.setCellFactory(v -> new AgentCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().addAll(title, list);
    }

    private static Label hintLabel(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }

    private static class AgentCell extends ListCell<AgentModel> {
        @Override
        protected void updateItem(AgentModel a, boolean empty) {
            super.updateItem(a, empty);
            if (empty || a == null) {
                setText(null);
                setGraphic(null);
                getStyleClass().remove("agent-card");
                return;
            }
            if (!getStyleClass().contains("agent-card")) getStyleClass().add("agent-card");

            Circle dot = new Circle(4);
            dot.getStyleClass().add(a.onlineProperty().get() ? "dot-online" : "dot-offline");
            Label name = new Label(a.getName());
            name.getStyleClass().add("agent-name");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Label plat = new Label(a.platform());
            plat.getStyleClass().add("agent-meta");
            HBox top = new HBox(8, dot, name, sp, plat);
            top.setAlignment(Pos.CENTER_LEFT);

            // Only the capabilities the agent actually has; a greyed badge is
            // noise, not information.
            HBox badges = new HBox(6);
            if (a.caps.dpdk) badges.getChildren().add(badge("DPDK", "badge-on"));
            if (a.caps.dscp) badges.getChildren().add(badge("DSCP", "badge-on"));
            if (a.caps.sack) badges.getChildren().add(badge("SACK", "badge-on"));
            badges.getChildren().add(badge(a.caps.cpuCount + " cpu", "badge-info"));
            badges.setAlignment(Pos.CENTER_LEFT);

            Label addr = new Label("data @ " + a.getDataAddr());
            addr.getStyleClass().addAll("agent-meta", "mono");

            VBox box = new VBox(6, top, badges, addr);
            setGraphic(box);
            setText(null);
        }

        private static Label badge(String text, String style) {
            Label l = new Label(text);
            l.getStyleClass().addAll("badge", style);
            return l;
        }
    }
}
