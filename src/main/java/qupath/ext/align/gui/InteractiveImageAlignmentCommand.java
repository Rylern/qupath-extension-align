/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2025 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.align.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;

/**
 * Command to interactively adjust apply an affine transform to an image overlay.
 * 
 * @author Pete Bankhead
 */
class InteractiveImageAlignmentCommand implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(InteractiveImageAlignmentCommand.class);
	private final QuPathGUI qupath;
	private ImageAlignmentWindow imageAlignmentWindow;
	
	/**
	 * Constructor.
	 *
	 * @param qupath the QuPath GUI that should own this command
	 */
	public InteractiveImageAlignmentCommand(QuPathGUI qupath) {
		this.qupath = qupath;
	}

	@Override
	public void run() {
		if (imageAlignmentWindow == null) {
            try {
                imageAlignmentWindow = new ImageAlignmentWindow(qupath);
            } catch (IOException e) {
				logger.error("Error while creating image overlay alignment window", e);
				return;
            }
        }
		imageAlignmentWindow.show();
		imageAlignmentWindow.requestFocus();

		new ImageAlignmentPane(qupath);
	}
}