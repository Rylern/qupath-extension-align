package qupath.ext.align.gui;

import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.transform.Affine;
import javafx.scene.transform.MatrixType;
import javafx.scene.transform.NonInvertibleTransformException;
import javafx.scene.transform.TransformChangedEvent;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.controlsfx.control.CheckListView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.align.core.AlignmentType;
import qupath.ext.align.core.AutoAligner;
import qupath.ext.align.core.RegistrationType;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.images.stores.ImageRenderer;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.GeometryTools;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ImageAlignmentWindow extends Stage {

    private static final Logger logger = LoggerFactory.getLogger(ImageAlignmentWindow.class);
    private static final ResourceBundle resources = Utils.getResources();
    private static final double DEFAULT_OPACITY = 1;
    private static final int DEFAULT_ROTATION_INCREMENT = 1;
    private static final double DEFAULT_PIXEL_SIZE = 20;
    private static final UnaryOperator<TextFormatter.Change> FLOAT_FILTER = change ->
            Pattern.matches("^\\d*\\.?\\d*$", change.getControlNewText()) ? change : null;
    private final Map<ImageData<BufferedImage>, ImageServerOverlay> imageDataToOverlay = new WeakHashMap<>();
    private final EventHandler<TransformChangedEvent> transformEventHandler = event -> affineTransformUpdated();
    private final QuPathGUI quPath;
    private record ImageDataRenderer(ImageData<BufferedImage> imageData, ImageRenderer renderer) {}
    @FXML
    private CheckListView<ImageData<BufferedImage>> images;
    @FXML
    private Button chooseImages;
    @FXML
    private Slider opacity;
    @FXML
    private TextField rotationIncrement;
    @FXML
    private Button rotateLeft;
    @FXML
    private Button rotateRight;
    @FXML
    private ComboBox<RegistrationType> registrationType;
    @FXML
    private ComboBox<AlignmentType> alignmentType;
    @FXML
    private TextField pixelSize;
    @FXML
    private Button estimateTransform;
    @FXML
    private TextArea affineTransformation;
    @FXML
    private Button update;
    @FXML
    private Button invert;
    @FXML
    private Button reset;
    @FXML
    private Button copy;
    @FXML
    private Button propagate;

    public ImageAlignmentWindow(QuPathGUI quPath) throws IOException {
        this.quPath = quPath;

        Utils.loadFXML(this, ImageAlignmentWindow.class.getResource("image_overlay_alignment_window.fxml"));

        images.setCellFactory(c -> new ImageEntryCell());
        chooseImages.disableProperty().bind(quPath.projectProperty().isNull());
        opacity.setValue(DEFAULT_OPACITY);
        //TODO: bind ImageServerOverlay opacity with opacity slider

        rotationIncrement.setText(String.valueOf(DEFAULT_ROTATION_INCREMENT));
        rotationIncrement.setTextFormatter(new TextFormatter<>(FLOAT_FILTER));

        rotateLeft.disableProperty().bind(images.getSelectionModel().selectedItemProperty().isNull());
        rotateRight.disableProperty().bind(images.getSelectionModel().selectedItemProperty().isNull());

        registrationType.getItems().setAll(RegistrationType.values());
        registrationType.setConverter(new StringConverter<>() {
            @Override
            public String toString(RegistrationType object) {
                return switch (object) {
                    case AFFINE -> resources.getString("ImageOverlayAlignmentWindow.affineTransform");
                    case RIGID -> resources.getString("ImageOverlayAlignmentWindow.rigidTransform");
                };
            }

            @Override
            public RegistrationType fromString(String string) {
                return RegistrationType.valueOf(string);
            }
        });
        registrationType.getSelectionModel().select(RegistrationType.AFFINE);
        alignmentType.getItems().setAll(AlignmentType.values());
        alignmentType.setConverter(new StringConverter<>() {
            @Override
            public String toString(AlignmentType object) {
                return switch (object) {
                    case INTENSITY -> resources.getString("ImageOverlayAlignmentWindow.imageIntensity");
                    case AREA_ANNOTATIONS -> resources.getString("ImageOverlayAlignmentWindow.areaAnnotations");
                    case POINT_ANNOTATIONS -> resources.getString("ImageOverlayAlignmentWindow.pointAnnotations");
                };
            }

            @Override
            public AlignmentType fromString(String string) {
                return AlignmentType.valueOf(string);
            }
        });
        alignmentType.getSelectionModel().select(AlignmentType.INTENSITY);
        pixelSize.setText(String.valueOf(DEFAULT_PIXEL_SIZE));
        pixelSize.setTextFormatter(new TextFormatter<>(FLOAT_FILTER));
        estimateTransform.disableProperty().bind(images.getSelectionModel().selectedItemProperty().isNull());

        affineTransformation.editableProperty().bind(images.getSelectionModel().selectedItemProperty().isNotNull());
        for (Button button: List.of(update, invert, reset, copy, propagate)) {
            button.disableProperty().bind(images.getSelectionModel().selectedItemProperty().isNull());
        }

        EventHandler<MouseEvent> viewerMouseEventHandler = new EventHandler<>() {
            private Point2D pDragging;

            @Override
            public void handle(MouseEvent event) {
                if (!event.isPrimaryButtonDown() || event.isConsumed()) {
                    logger.trace("Primary button not pressed or mouse event {} already consumed. Not doing anything", event);
                    return;
                }

                Optional<ImageServerOverlay> overlay = getSelectedOverlay();
                if (overlay.isEmpty()) {
                    logger.trace("No overlay currently active. Not doing anything");
                    return;
                }

                QuPathViewer viewer = quPath.viewerProperty().get();
                if (viewer == null) {
                    logger.trace("No viewer currently active. Not doing anything");
                    return;
                }

                if (event.getEventType() == MouseEvent.MOUSE_PRESSED) {
                    pDragging = viewer.componentPointToImagePoint(event.getX(), event.getY(), pDragging, true);
                    logger.trace("Mouse pressed. Setting dragging point to {}", pDragging);
                } else if (event.getEventType() == MouseEvent.MOUSE_DRAGGED) {
                    Point2D point = viewer.componentPointToImagePoint(event.getX(), event.getY(), null, true);
                    if (event.isShiftDown() && pDragging != null) {
                        double dx = pDragging.getX() - point.getX();
                        double dy = pDragging.getY() - point.getY();
                        overlay.get().getAffine().appendTranslation(dx, dy);      //TODO: can affine be null?
                        event.consume();
                        logger.trace("Mouse dragged and shift modifier down. {} dragged by [{}, {}] and {} consumed", overlay.get(), dx, dy, event);
                    } else {
                        logger.trace("Mouse dragged. Setting dragging point to {}", pDragging);
                    }
                    pDragging = point;
                }
            }
        };
        ChangeListener<? super QuPathViewer> viewerListener = (ChangeListener<QuPathViewer>) (p, o, n) -> {
            if (o != null) {
                o.getView().removeEventFilter(MouseEvent.ANY, viewerMouseEventHandler);
            }
            if (n != null) {
                n.getView().addEventFilter(MouseEvent.ANY, viewerMouseEventHandler);
            }
        };
        showingProperty().addListener((p, o, n) -> {
            if (n) {
                if (quPath.viewerProperty().get() != null) {
                    quPath.viewerProperty().get().getView().addEventFilter(MouseEvent.ANY, viewerMouseEventHandler);
                }
                quPath.viewerProperty().addListener(viewerListener);
            } else {
                if (quPath.viewerProperty().get() != null) {
                    quPath.viewerProperty().get().getView().removeEventFilter(MouseEvent.ANY, viewerMouseEventHandler);
                }
                quPath.viewerProperty().removeListener(viewerListener);
            }
        });

        initOwner(quPath.getStage());
    }

    @FXML
    private void onChooseImagesClicked(ActionEvent ignored) {
        Project<BufferedImage> project = quPath.projectProperty().get();
        if (project == null) {
            logger.error("No project currently open. Cannot open images selector");
            return;
        }

        ImagesSelector imagesSelector;
        try {
            imagesSelector = new ImagesSelector(
                    project.getImageList(),
                    images.getItems().stream()
                            .map(project::getEntry)
                            .toList()
            );
        } catch (IOException e) {
            logger.error("Error while creating images selector pane", e);
            return;
        }

        if (Dialogs.showMessageDialog(resources.getString("ImageOverlayAlignmentWindow.selectImagesToInclude"), imagesSelector)) {
            return;
        }

        List<ImageData<BufferedImage>> imagesToRemove = images.getItems().stream()
                .filter(imageData -> imagesSelector.getUnselectedImages().contains(project.getEntry(imageData)))
                .toList();
        images.getItems().removeAll(imagesToRemove);
        for (ImageData<BufferedImage> image: imagesToRemove) {
            //TODO: check ca
            ImageServerOverlay overlay = imageDataToOverlay.remove(image);
            if (overlay != null) {
                overlay.getAffine().removeEventHandler(TransformChangedEvent.ANY, transformEventHandler);
                quPath.getViewer().getCustomOverlayLayers().remove(overlay);    //TODO: handle changes in viewer
            }
        }

        List<ImageDataRenderer> imageDataRenderersToAdd = imagesSelector.getSelectedImages().stream()
                .map(entry -> {
                    // Try to get data from an open viewer first, if possible
                    for (QuPathViewer viewer : quPath.getAllViewers()) {
                        var imageData = viewer.getImageData();

                        if (imageData != null && entry.equals(project.getEntry(viewer.getImageData()))) {
                            return new ImageDataRenderer(imageData, viewer.getImageDisplay());
                        }
                    }

                    // Read the data from the project if necessary
                    try {
                        if (entry.hasImageData()) {
                            ImageData<BufferedImage> imageData = entry.readImageData();

                            // Remove non-annotations to save memory
                            Collection<PathObject> pathObjects = imageData.getHierarchy().getObjects(null, null);
                            Set<PathObject> pathObjectsToRemove = pathObjects.stream().filter(p -> !p.isAnnotation()).collect(Collectors.toSet());
                            imageData.getHierarchy().removeObjects(pathObjectsToRemove, true);

                            return new ImageDataRenderer(imageData, null);
                        } else {
                            // Read the data from the project (but without a data file we expect this to really create a new image)
                            return new ImageDataRenderer(entry.readImageData(), null);
                        }
                    } catch (IOException e) {
                        logger.error("Unable to read ImageData for {}. Cannot add it to the image list", entry, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        for (var imageDataRenderer: imageDataRenderersToAdd) {
            ImageServerOverlay overlay = new ImageServerOverlay(quPath.getViewer(), imageDataRenderer.imageData().getServer());
            overlay.setRenderer(imageDataRenderer.renderer());
            overlay.getAffine().addEventHandler(TransformChangedEvent.ANY, transformEventHandler);
            imageDataToOverlay.put(imageDataRenderer.imageData(), overlay);
        }
        images.getItems().addAll(0, imageDataRenderersToAdd.stream().map(ImageDataRenderer::imageData).toList());
    }

    @FXML
    private void onRotateLeftClicked(ActionEvent ignored) {
        rotate(1);
    }

    @FXML
    private void onRotateRightClicked(ActionEvent ignored) {
        rotate(-1);
    }

    @FXML
    private void onEstimateTransformClicked(ActionEvent ignored) {
        double pixelSizeMicrons;
        try {
            pixelSizeMicrons = Double.parseDouble(pixelSize.getText());
        } catch (NumberFormatException e) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.alignmentError"),
                    MessageFormat.format(
                            resources.getString("ImageOverlayAlignmentWindow.pixelSizeCannotBeConvertedToNumber"),
                            pixelSize.getText()
                    )
            );
            return;
        }

        ImageData<BufferedImage> imageDataBase = quPath.getViewer().getImageData();
        ImageData<BufferedImage> imageDataSelected = images.getSelectionModel().selectedItemProperty().get();
        if (imageDataBase == null) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.alignmentError"),
                    resources.getString("ImageOverlayAlignmentWindow.noImageAvailable")
            );
            return;
        }
        if (imageDataSelected == null) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.alignmentError"),
                    resources.getString("ImageOverlayAlignmentWindow.ensureImageOverlaySelected")
            );
            return;
        }
        if (imageDataBase == imageDataSelected) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.alignmentError"),
                    resources.getString("ImageOverlayAlignmentWindow.selectImageOverlay")
            );
            return;
        }

        try {
            AutoAligner.align(
                    imageDataBase,
                    imageDataSelected,
                    imageDataToOverlay.get(imageDataSelected),
                    alignmentType.getValue(),
                    registrationType.getValue(),
                    pixelSizeMicrons
            );
        } catch (Exception e) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.alignmentError"),
                    MessageFormat.format(
                            resources.getString("ImageOverlayAlignmentWindow.errorRequestingImageRegion"), //TODO: change message
                            e.getLocalizedMessage()
                    )
            );
            logger.error("Error in auto alignment", e);
        }
    }

    @FXML
    private void onUpdateClicked(ActionEvent ignored) {
        ImageServerOverlay overlay = imageDataToOverlay.get(images.getSelectionModel().selectedItemProperty().get());
        if (overlay == null) {
            //TODO: check if necessary
            logger.debug("Overlay null. Cannot update affine from text {}", affineTransformation.getText());
            return;
        }
        Affine affine = overlay.getAffine();
        if (affine == null) {
            //TODO: check if necessary
            logger.debug("Affine of {} null. Cannot update affine from text {}", overlay, affineTransformation.getText());
            return;
        }

        try {
            double[] values = GeometryTools.parseTransformMatrix(affineTransformation.getText()).getMatrixEntries();
            affine.setToTransform(values[0], values[1], values[2], values[3], values[4], values[5]); // JavaFX's Affine has a different element ordering than awt's AffineTransform
        } catch (ParseException e) {
            logger.error("Cannot parse transform {}. {} not updated", affineTransformation.getText(), affine, e);

            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.parseAffineTransform"),
                    resources.getString("ImageOverlayAlignmentWindow.unableToParseAffineTransform")
            );
        }
    }

    @FXML
    private void onInvertClicked(ActionEvent ignored) {
        ImageServerOverlay overlay = imageDataToOverlay.get(images.getSelectionModel().selectedItemProperty().get());
        if (overlay == null) {
            //TODO: check if necessary
            logger.debug("Overlay null. Cannot invert affine");
            return;
        }
        Affine affine = overlay.getAffine();
        if (affine == null) {
            //TODO: check if necessary
            logger.debug("Affine of {} null. Cannot invert affine", overlay);
            return;
        }

        try {
            affine.invert();
        } catch (NonInvertibleTransformException e) {
            logger.error("Cannot invert {}", affine, e);

            Dialogs.showErrorNotification(
                    resources.getString("ImageOverlayAlignmentWindow.invertTransform"),
                    resources.getString("ImageOverlayAlignmentWindow.transformNotInvertible")
            );
        }
    }

    @FXML
    private void onResetClicked(ActionEvent ignored) {
        ImageServerOverlay overlay = imageDataToOverlay.get(images.getSelectionModel().selectedItemProperty().get());
        if (overlay == null) {
            //TODO: check if necessary
            logger.debug("Overlay null. Cannot reset affine");
            return;
        }

        overlay.resetAffine();

        Dialogs.showInfoNotification(
                resources.getString("ImageOverlayAlignmentWindow.resetTransform"),
                resources.getString("ImageOverlayAlignmentWindow.transformReset")
        );
    }

    @FXML
    private void onCopyClicked(ActionEvent ignored) {
        ImageServerOverlay overlay = imageDataToOverlay.get(images.getSelectionModel().selectedItemProperty().get());
        if (overlay == null) {
            //TODO: check if necessary
            logger.debug("Overlay null. Cannot copy affine");
            return;
        }
        Affine affine = overlay.getAffine();
        if (affine == null) {
            //TODO: check if necessary
            logger.debug("Affine of {} null. Cannot copy affine", overlay);
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(String.format(
                "%f,\t%f,\t%f\n,%f,\t%f,\t%f",
                affine.getElement(MatrixType.MT_2D_2x3, 0, 0),
                affine.getElement(MatrixType.MT_2D_2x3, 0, 1),
                affine.getElement(MatrixType.MT_2D_2x3, 0, 2),
                affine.getElement(MatrixType.MT_2D_2x3, 1, 0),
                affine.getElement(MatrixType.MT_2D_2x3, 1, 1),
                affine.getElement(MatrixType.MT_2D_2x3, 1, 2)
        ));
        Clipboard.getSystemClipboard().setContent(content);

        Dialogs.showInfoNotification(
                resources.getString("ImageOverlayAlignmentWindow.copyTransform"),
                resources.getString("ImageOverlayAlignmentWindow.transformCopied")
        );
    }

    @FXML
    private void onPropagateClicked(ActionEvent ignored) {
        Project<BufferedImage> project = quPath.getProject();
        if (project == null) {
            //TODO: check if necessary. Dialog?
            logger.debug("No project currently open. Cannot propagate annotations");
            return;
        }

        ImageData<BufferedImage> imageDataBase = quPath.getViewer().getImageData();
        ImageData<BufferedImage> imageDataSelected = images.getSelectionModel().selectedItemProperty().get();
        if (imageDataBase == null) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.propagateAnnotations"),
                    resources.getString("ImageOverlayAlignmentWindow.noImageAvailable")
            );
            return;
        }
        if (imageDataSelected == null) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.propagateAnnotations"),
                    resources.getString("ImageOverlayAlignmentWindow.ensureImageOverlaySelected")
            );
            return;
        }
        if (imageDataBase == imageDataSelected) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.propagateAnnotations"),
                    resources.getString("ImageOverlayAlignmentWindow.selectImageOverlay")
            );
            return;
        }
        ProjectImageEntry<BufferedImage> imageEntrySelected = project.getEntry(imageDataSelected);
        if (imageEntrySelected == null) {
            //TODO: check if necessary. Dialog?
            logger.debug("No project entry for {} in {}. Cannot propagate annotations", imageDataSelected, project);
            return;
        }

        ImageServerOverlay overlay = imageDataToOverlay.get(images.getSelectionModel().selectedItemProperty().get());
        if (overlay == null) {
            //TODO: check if necessary
            logger.debug("Overlay null. Cannot propagate annotations");
            return;
        }

        imageDataSelected.getHierarchy().addObjects(imageDataBase.getHierarchy().getAnnotationObjects().stream()
                .map(overlay::transformObject)   // TODO: check always safe
                .toList()
        );
        try {
            imageEntrySelected.saveImageData(imageDataSelected);
        } catch (IOException e) {
            logger.error("Cannot save image data {}. Annotations not propagated", imageDataSelected, e);

            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.propagateAnnotations"),
                    resources.getString("ImageOverlayAlignmentWindow.cannotSaveImageData")
            );
        }
    }

    private void affineTransformUpdated() {
        ImageServerOverlay overlay = imageDataToOverlay.get(images.getSelectionModel().selectedItemProperty().get());
        if (overlay == null) {
            affineTransformation.setText("No overlay selected");
            return;
        }

        Affine affine = overlay.getAffine();
        affineTransformation.setText(
                String.format(
                        "%.4f, \t %.4f,\t %.4f,\n" +
                        "%.4f,\t %.4f,\t %.4f",
                        affine.getMxx(), affine.getMxy(), affine.getTx(),
                        affine.getMyx(), affine.getMyy(), affine.getTy()
                )
        );
    }

    private Optional<ImageServerOverlay> getSelectedOverlay() {
        ImageData<BufferedImage> selectedImage = images.getSelectionModel().selectedItemProperty().get();
        if (selectedImage == null) {
            return Optional.empty();
        }

        ImageServerOverlay overlay;
        if (imageDataToOverlay.containsKey(selectedImage)) {
            overlay = imageDataToOverlay.get(selectedImage);
        } else {
            overlay = new ImageServerOverlay(quPath.getViewer(), selectedImage.getServer());        //TODO: not viewer property?
            overlay.setRenderer(quPath.getAllViewers().stream()
                    .filter(viewer -> viewer.getImageData().equals(selectedImage))
                    .map(QuPathViewer::getImageDisplay)
                    .findAny()
                    .orElse(null)
            );  //TODO: can be null?
            overlay.getAffine().addEventHandler(TransformChangedEvent.ANY, transformEventHandler);
            imageDataToOverlay.put(selectedImage, overlay);
        }

        return Optional.of(overlay);
    }

    private void rotate(int sign) {
        double theta;
        try {
            theta = sign * Double.parseDouble(rotationIncrement.getText());
        } catch (NumberFormatException e) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.rotateOverlay"),
                    MessageFormat.format(
                            resources.getString("ImageOverlayAlignmentWindow.rotationCannotBeConvertedToNumber"),
                            rotationIncrement.getText()
                    )
            );
            return;
        }

        ImageServerOverlay overlay = imageDataToOverlay.get(images.getSelectionModel().selectedItemProperty().get());
        if (overlay == null) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.rotateOverlay"),
                    resources.getString("ImageOverlayAlignmentWindow.noOverlaySelected")
            );
            return;
        }

        QuPathViewer viewer = quPath.getViewer();
        if (viewer == null) {
            Dialogs.showErrorMessage(
                    resources.getString("ImageOverlayAlignmentWindow.rotateOverlay"),
                    resources.getString("ImageOverlayAlignmentWindow.noActiveViewer")
            );
            return;
        }

        overlay.getAffine().appendRotation(theta, viewer.getCenterPixelX(), viewer.getCenterPixelY());
    }
}
