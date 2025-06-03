package qupath.ext.align.gui;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.EventHandler;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;

import java.awt.geom.Point2D;

class ViewerMouseEventHandler {

    private final EventHandler<MouseEvent> mouseEventHandler;
    private final ChangeListener<? super QuPathViewer> viewerListener;

    public ViewerMouseEventHandler(Stage stage, QuPathGUI quPath, ObservableValue<ImageServerOverlay> imageServerOverlayProperty) {
        this.mouseEventHandler = new EventHandler<>() {
            private Point2D pDragging;

            @Override
            public void handle(MouseEvent event) {
                if (!event.isPrimaryButtonDown() || event.isConsumed()) {
                    return;
                }

                ImageServerOverlay overlay = imageServerOverlayProperty.getValue();
                if (overlay == null) {
                    return;
                }

                QuPathViewer viewer = quPath.viewerProperty().get();
                if (viewer == null) {
                    return;
                }

                if (event.getEventType() == MouseEvent.MOUSE_PRESSED) {
                    pDragging = viewer.componentPointToImagePoint(event.getX(), event.getY(), pDragging, true);
                } else if (event.getEventType() == MouseEvent.MOUSE_DRAGGED) {
                    Point2D point = viewer.componentPointToImagePoint(event.getX(), event.getY(), null, true);
                    if (event.isShiftDown() && pDragging != null) {
                        double dx = point.getX() - pDragging.getX();
                        double dy = point.getY() - pDragging.getY();
                        overlay.getAffine().appendTranslation(-dx, -dy);
                        event.consume();
                    }
                    pDragging = point;
                }
            }
        };

        this.viewerListener = (ChangeListener<QuPathViewer>) (p, o, n) -> {
            if (o != null) {
                o.getView().removeEventFilter(MouseEvent.ANY, mouseEventHandler);
            }
            if (n != null) {
                n.getView().addEventFilter(MouseEvent.ANY, mouseEventHandler);
            }
        };

        stage.showingProperty().addListener((p, o, n) -> {
            if (n) {
                if (quPath.viewerProperty().get() != null) {
                    quPath.viewerProperty().get().getView().addEventFilter(MouseEvent.ANY, mouseEventHandler);
                }
                quPath.viewerProperty().addListener(viewerListener);
            } else {
                if (quPath.viewerProperty().get() != null) {
                    quPath.viewerProperty().get().getView().removeEventFilter(MouseEvent.ANY, mouseEventHandler);
                }
                quPath.viewerProperty().removeListener(viewerListener);
            }
        });
    }
}
