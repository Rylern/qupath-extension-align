package qupath.ext.align.gui;

import javafx.fxml.FXMLLoader;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * Utility methods for the whole extension.
 */
public class Utils {

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.align.strings");

    private Utils() {
        throw new AssertionError("This class is not instantiable.");
    }

    /**
     * @return the resources containing the localized strings of the extension
     */
    public static ResourceBundle getResources() {
        return resources;
    }

    /**
     * Loads the FXML file located at the URL and set its controller.
     *
     * @param controller the controller of the FXML file to load
     * @param url the path of the FXML file to load
     * @throws IOException if an error occurs while loading the FXML file
     */
    public static void loadFXML(Object controller, URL url) throws IOException {
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(controller);
        loader.setController(controller);
        loader.load();
    }
}
