package qupath.ext.align.gui;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableDoubleValue;
import javafx.beans.value.ObservableValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.align.core.ImageTransform;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * An {@link AbstractOverlay} that applies an affine transformation on an image and displays the result.
 * <p>
 * This overlay must be {@link #close() closed} once no longer used.
 */
class AlignOverlay extends AbstractOverlay implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AlignOverlay.class);
    private final QuPathViewer viewer;
    private final ObservableValue<ImageTransform> observableImageTransform;
    private final ObservableDoubleValue opacity;
    private final ChangeListener<? super Number> opacityListener;
    private final ChangeListener<? super ImageTransform> transformListener;

    /**
     * Create the overlay and add it to the provided viewer's {@link QuPathViewer#getCustomOverlayLayers() list of custom overlay layers}.
     * The provided viewer will be asked to be repainted when the value of provided image transform changes.
     *
     * @param viewer the viewer on which the overlay will be placed
     * @param observableImageTransform an observable value that contains the image (and the transform to apply to it) to display
     *                                 when this overlay is painted. It cannot be null but its value can
     * @param opacity an observable value containing the opacity this overlay should have. When the value changes, the provided viewer
     *                is asked to be repainted, so that this overlay displays the correct opacity. This observable must be updated from
     *                the JavaFX Application Thread. It cannot be null but its value can
     * @throws NullPointerException if one of the provided parameters is null
     */
    public AlignOverlay(QuPathViewer viewer, ObservableValue<ImageTransform> observableImageTransform, ObservableDoubleValue opacity) {
        super(viewer.getOverlayOptions());

        logger.debug("Creating overlay for {}", viewer);

        this.viewer = viewer;
        this.observableImageTransform = Objects.requireNonNull(observableImageTransform);
        this.opacity = opacity;
        this.opacityListener = (p, o, n) -> {
            setOpacity(n.doubleValue());
            logger.trace("Opacity updated to {}. Asking to repaint {}", n, viewer);
            viewer.repaint();
        };
        this.transformListener = (p, o, n) -> {
            logger.trace("Image transform updated to {}. Asking to repaint {}", n, viewer);
            viewer.repaint();
        };

        viewer.getCustomOverlayLayers().add(this);

        opacity.addListener(opacityListener);
        opacityListener.changed(opacity, null, opacity.getValue());

        observableImageTransform.addListener(transformListener);
        transformListener.changed(observableImageTransform, null, observableImageTransform.getValue());
    }

    @Override
    public void paintOverlay(Graphics2D g2d, ImageRegion imageRegion, double downsampleFactor, ImageData<BufferedImage> imageData, boolean paintCompletely) {
        ImageTransform imageTransform = observableImageTransform.getValue();
        if (imageTransform == null) {
            logger.trace("No current image transform. ");
            return;
        }

        logger.trace("Painting align overlay to {} with {}", viewer, imageTransform);

        Graphics2D graphics = (Graphics2D) g2d.create();

        AffineTransform transform = graphics.getTransform();
        transform.concatenate(imageTransform.getInverseTransform());
        graphics.setTransform(transform);

        AlphaComposite composite = getAlphaComposite();
        if (composite != null) {
            graphics.setComposite(composite);
        }

        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                PathPrefs.viewerInterpolateBilinearProperty().get() ?
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR :
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );

        viewer.getImageRegionStore().paintRegion(
                imageTransform.getImageServer(),
                graphics,
                graphics.getClip(),
                imageRegion.getZ(),
                imageRegion.getT(),
                downsampleFactor,
                null,
                null,
                viewer.getImageDisplay()
        );

        graphics.dispose();
    }

    @Override
    public String toString() {
        return String.format(
                "Align overlay of %s with current transform %s and opacity %f",
                viewer,
                observableImageTransform.getValue(),
                opacity.get()
        );
    }

    /**
     * Close this overlay and remove it from the provided viewer's {@link QuPathViewer#getCustomOverlayLayers() list of custom overlay layers}.
     */
    @Override
    public void close() {
        viewer.getCustomOverlayLayers().remove(this);

        opacity.removeListener(opacityListener);
        observableImageTransform.removeListener(transformListener);

        logger.debug("Overlay for {} closed", viewer);
    }
}
