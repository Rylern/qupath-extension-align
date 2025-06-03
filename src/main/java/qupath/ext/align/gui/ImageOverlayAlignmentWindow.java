package qupath.ext.align.gui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.transform.Affine;
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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ImageOverlayAlignmentWindow extends Stage {

    private static final Logger logger = LoggerFactory.getLogger(ImageOverlayAlignmentWindow.class);
    private static final ResourceBundle resources = Utils.getResources();
    private static final double DEFAULT_ROTATION_INCREMENT = 1;
    private static final double DEFAULT_PIXEL_SIZE = 20;
    private static final UnaryOperator<TextFormatter.Change> FLOAT_FILTER = change ->
            Pattern.matches("^\\d*\\.?\\d*$", change.getControlNewText()) ? change : null;
    private final Map<ImageData<BufferedImage>, ImageServerOverlay> mapOverlays = new WeakHashMap<>();
    private final ObjectProperty<ImageData<BufferedImage>> selectedImageData = new SimpleObjectProperty<>();
    private final ObjectBinding<ImageServerOverlay> selectedOverlay = Bindings.createObjectBinding(
            () -> mapOverlays.get(selectedImageData.get()),
            selectedImageData
    );
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

    public ImageOverlayAlignmentWindow(QuPathGUI quPath) throws IOException {
        this.quPath = quPath;

        Utils.loadFXML(this, ImageOverlayAlignmentWindow.class.getResource("image_overlay_alignment_window.fxml"));

        images.setCellFactory(c -> new ImageEntryCell());
        chooseImages.disableProperty().bind(quPath.projectProperty().isNull());
        //TODO: bind opacity slider with ImageServerOverlay opacity

        rotationIncrement.setText(String.valueOf(DEFAULT_ROTATION_INCREMENT));
        rotationIncrement.setTextFormatter(new TextFormatter<>(FLOAT_FILTER));
        rotateLeft.disableProperty().bind(selectedOverlay.isNull());
        rotateRight.disableProperty().bind(selectedOverlay.isNull());

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

        affineTransformation.editableProperty().bind(selectedOverlay.isNotNull());
        for (Button button: List.of(update, invert, reset, copy, propagate)) {
            button.disableProperty().bind(selectedOverlay.isNull());
        }

        new ViewerMouseEventHandler(this, quPath, selectedOverlay);

        initOwner(quPath.getStage());
    }

    @FXML
    private void onChooseImagesClicked(ActionEvent ignored) {
        Project<BufferedImage> project = quPath.projectProperty().get();
        if (project == null) {
            // TODO: display error message saying no project open
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

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setHeaderText(resources.getString("ImageOverlayAlignmentWindow.selectImagesToInclude"));
        dialog.getDialogPane().setContent(imagesSelector);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) {
            return;
        }

        List<ImageData<BufferedImage>> imagesToRemove = images.getItems().stream()
                .filter(imageData -> imagesSelector.getUnselectedImages().contains(project.getEntry(imageData)))
                .toList();
        images.getItems().removeAll(imagesToRemove);
        for (ImageData<BufferedImage> image: imagesToRemove) {
            //TODO: check ca
            ImageServerOverlay overlay = mapOverlays.remove(image);
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
            mapOverlays.put(imageDataRenderer.imageData(), overlay);
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
        ImageData<BufferedImage> imageDataSelected = selectedImageData.get();
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
                    mapOverlays.get(imageDataSelected),
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
        //TODO
    }

    @FXML
    private void onInvertClicked(ActionEvent ignored) {
        //TODO
    }

    @FXML
    private void onResetClicked(ActionEvent ignored) {
        //TODO
    }

    @FXML
    private void onCopyClicked(ActionEvent ignored) {
        //TODO
    }

    @FXML
    private void onPropagateClicked(ActionEvent ignored) {
        //TODO
    }

    private void affineTransformUpdated() {
        ImageServerOverlay overlay = mapOverlays.get(selectedImageData.get());
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

        ImageServerOverlay overlay = mapOverlays.get(selectedImageData.get());
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
