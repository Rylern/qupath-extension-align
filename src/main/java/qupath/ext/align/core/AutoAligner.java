package qupath.ext.align.core;

import javafx.scene.transform.Affine;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.Indexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_video;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatExpr;
import org.bytedeco.opencv.opencv_core.TermCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.align.gui.ImageServerOverlay;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.tools.OpenCVTools;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AutoAligner {

    private static final Logger logger = LoggerFactory.getLogger(AutoAligner.class);
    private static final int MAX_WIDTH_WHEN_DETERMINING_DOWNSAMPLE = 2000;
    private static final int ECC_MAX_COUNT = 100;
    private static final double ECC_EPSILON = 0.0001;

    public static void align(
            ImageData<BufferedImage> imageDataBase,
            ImageData<BufferedImage> imageDataSelected,
            ImageServerOverlay overlay,
            AlignmentType alignmentType,
            RegistrationType registrationType,
            double pixelSizeMicrons
    ) throws Exception {
        Affine affine = overlay.getAffine();

        switch (alignmentType) {
            case INTENSITY -> {
                logger.debug("Image alignment of {} on {} using intensities", imageDataSelected, imageDataBase);

                alignWithEccCriterion(imageDataBase.getServer(), imageDataSelected.getServer(), registrationType, affine, pixelSizeMicrons);
            }
            case AREA_ANNOTATIONS -> {
                logger.debug("Image alignment of {} on {} using area annotations", imageDataSelected, imageDataBase);

                Map<PathClass, Integer> labels = new LinkedHashMap<>();
                int label = 1;
                labels.put(PathClass.NULL_CLASS, label++);
                for (var annotation : imageDataBase.getHierarchy().getAnnotationObjects()) {
                    var pathClass = annotation.getPathClass();
                    if (pathClass != null && !labels.containsKey(pathClass)) {
                        labels.put(pathClass, label++);
                    }
                }
                for (var annotation : imageDataSelected.getHierarchy().getAnnotationObjects()) {
                    var pathClass = annotation.getPathClass();
                    if (pathClass != null && !labels.containsKey(pathClass)) {
                        labels.put(pathClass, label++);
                    }
                }

                alignWithEccCriterion(
                        new LabeledImageServer.Builder(imageDataBase)
                                .backgroundLabel(0)
                                .addLabels(labels)
                                .downsample(pixelSizeMicrons / imageDataBase.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue())
                                .build(),
                        new LabeledImageServer.Builder(imageDataSelected)
                                .backgroundLabel(0)
                                .addLabels(labels)
                                .downsample(pixelSizeMicrons / imageDataSelected.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue())
                                .build(),
                        registrationType,
                        affine,
                        pixelSizeMicrons
                );
            }
            case POINT_ANNOTATIONS -> {
                logger.debug("Image alignment of {} on {} using point annotations", imageDataSelected, imageDataBase);

                alignWithPoints(affine, imageDataBase, imageDataSelected, registrationType);
            }
        }
    }

    private static void alignWithEccCriterion(
            ImageServer<BufferedImage> serverBase,
            ImageServer<BufferedImage> serverOverlay,
            RegistrationType registrationType,
            Affine affine,
            double pixelSizeMicrons
    ) throws Exception {
        double basePixelSizeMicrons = serverBase.getPixelCalibration().getAveragedPixelSizeMicrons();
        double downsample = 1;
        if (Double.isFinite(basePixelSizeMicrons)) {
            downsample = pixelSizeMicrons / basePixelSizeMicrons;
        } else {
            while (serverBase.getWidth() / downsample > MAX_WIDTH_WHEN_DETERMINING_DOWNSAMPLE) {
                downsample++;
            }
            logger.warn(
                    "Pixel size is unavailable! Default downsample value of {} will be used to auto align {} to {}",
                    downsample,
                    serverOverlay,
                    serverBase
            );
        }

        BufferedImage imgBase = ensureGrayScale(serverBase.readRegion(RegionRequest.createInstance(
                serverBase.getPath(),
                downsample,
                0,
                0,
                serverBase.getWidth(),
                serverBase.getHeight()
        )));
        BufferedImage imgOverlay = ensureGrayScale(serverOverlay.readRegion(RegionRequest.createInstance(
                serverOverlay.getPath(),
                downsample,
                0,
                0,
                serverOverlay.getWidth(),
                serverOverlay.getHeight()
        )));

        try (
                Mat matBase = OpenCVTools.imageToMat(imgBase);
                Mat matOverlay = OpenCVTools.imageToMat(imgOverlay);
                MatExpr matExprTransform = Mat.eye(2, 3, opencv_core.CV_32F);
                Mat matTransform = matExprTransform.asMat();
                FloatIndexer indexer = matTransform.createIndexer();
                TermCriteria termCriteria = new TermCriteria(TermCriteria.COUNT, ECC_MAX_COUNT, ECC_EPSILON)
        ) {
            indexer.put(0, 0, (float)affine.getMxx());
            indexer.put(0, 1, (float)affine.getMxy());
            indexer.put(0, 2, (float)(affine.getTx() / downsample));
            indexer.put(1, 0, (float)affine.getMyx());
            indexer.put(1, 1, (float)affine.getMyy());
            indexer.put(1, 2, (float)(affine.getTy() / downsample));

            double result = opencv_video.findTransformECC(
                    matBase,
                    matOverlay,
                    matTransform,
                    switch (registrationType) {
                        case AFFINE -> opencv_video.MOTION_AFFINE;
                        case RIGID -> opencv_video.MOTION_EUCLIDEAN;
                    },
                    termCriteria,
                    null
            );
            logger.info("Transformation result of aligning {} to {}: {}", serverOverlay, serverBase, result);

            matToAffine(indexer, affine, downsample);
        }
    }

    private static void alignWithPoints(
            Affine affine,
            ImageData<BufferedImage> imageDataBase,
            ImageData<BufferedImage> imageDataSelected,
            RegistrationType registrationType
    ) {
        List<Point2> pointsBase = getPointsOfNonAreaRois(imageDataBase.getHierarchy().getAnnotationObjects());
        List<Point2> pointsSelected = getPointsOfNonAreaRois(imageDataSelected.getHierarchy().getAnnotationObjects());
        if (pointsBase.isEmpty() && pointsSelected.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "No points found for either image %s and %s!",
                    imageDataBase,
                    imageDataSelected
            ));
        }
        if (pointsBase.size() != pointsSelected.size()) {
            throw new IllegalArgumentException(String.format(
                    "Images %s and %s have different numbers of annotated points (%d & %d)",
                    imageDataBase,
                    imageDataSelected,
                    pointsBase.size(),
                    pointsSelected.size()
            ));
        }

        try (
                Mat matBase = pointsToMat(pointsBase);
                Mat matSelected = pointsToMat(pointsSelected);
                Mat transform = opencv_video.estimateRigidTransform(matBase, matSelected, registrationType == RegistrationType.AFFINE);
                FloatIndexer indexer = transform.createIndexer();
        ) {
            matToAffine(indexer, affine, 1.0);
        }
    }

    private static void matToAffine(Indexer indexer, Affine affine, double downsample) {
        affine.setToTransform(
                indexer.getDouble(0, 0),
                indexer.getDouble(0, 1),
                indexer.getDouble(0, 2) * downsample,
                indexer.getDouble(1, 0),
                indexer.getDouble(1, 1),
                indexer.getDouble(1, 2) * downsample
        );
    }

    private static BufferedImage ensureGrayScale(BufferedImage image) {
        return switch (image.getType()) {
            case BufferedImage.TYPE_BYTE_GRAY -> image;
            case BufferedImage.TYPE_BYTE_INDEXED -> new BufferedImage(
                    new ComponentColorModel(
                            ColorSpace.getInstance(ColorSpace.CS_GRAY),
                            new int[]{8},
                            false,
                            true,
                            Transparency.OPAQUE,
                            DataBuffer.TYPE_BYTE
                    ),
                    image.getRaster(),
                    false,
                    null
            );
            default -> {
                BufferedImage imgGray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D g2d = imgGray.createGraphics();
                g2d.drawImage(image, 0, 0, null);
                g2d.dispose();
                yield imgGray;
            }
        };
    }

    private static List<Point2> getPointsOfNonAreaRois(Collection<PathObject> pathObjects) {
        return pathObjects.stream()
                .map(PathObject::getROI)
                .filter(roi -> roi != null && !roi.isArea())
                .map(ROI::getAllPoints)
                .flatMap(List::stream)
                .toList();
    }

    private static Mat pointsToMat(List<Point2> points) {
        Mat mat = new Mat(points.size(), 2, opencv_core.CV_32FC1);
        try (FloatIndexer indexer = mat.createIndexer()) {
            for (int i=0; i<points.size(); i++) {
                indexer.put(i, 0, (float)points.get(i).getX());
                indexer.put(i, 1, (float)points.get(i).getY());
            }
        }
        return mat;
    }
}
