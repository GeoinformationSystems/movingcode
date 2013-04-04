/**
 * ﻿Copyright (C) 2012
 * by 52 North Initiative for Geospatial Open Source Software GmbH
 *
 * Contact: Andreas Wytzisk
 * 52 North Initiative for Geospatial Open Source Software GmbH
 * Martin-Luther-King-Weg 24
 * 48155 Muenster, Germany
 * info@52north.org
 *
 * This program is free software; you can redistribute and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 *
 * This program is distributed WITHOUT ANY WARRANTY; even without the implied
 * WARRANTY OF MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program (see gnu-gpl v2.txt). If not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA or
 * visit the Free Software Foundation web page, http://www.fsf.org.
 */
package org.n52.movingcode.runtime.coderepository;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.xmlbeans.XmlException;
import org.n52.movingcode.runtime.codepackage.MovingCodePackage;

import de.tudresden.gis.geoprocessing.movingcode.schema.PackageDescriptionDocument;

/**
 * This class implements an {@link IMovingCodeRepository} for local plain (unzipped) packages, stored
 * in a flat folder structure. This folder structure shall have the following appearance:
 * 
 * <absPath>-<folder1>-<packagedescription.xml>
 *          |         \<workspacefolder1>
 *          |
 *          -<folder2>-<packagedescription.xml>
 *          |         \<workspacefolder2>
 *          |
 *          -<folder3>-<packagedescription.xml>
 *          |         \<workspacefolder3>
 *          |
 *          ...
 *          |
 *          -<folderN>-<processdescriptionN>
 *                    \<workspacefolderN>
 * 
 * For any sub-folders found in <absPath> it will be assumed that it contains a plain (unzipped)
 * Moving Code package.
 * 
 * @author Matthias Mueller, TU Dresden
 *
 */
public final class LocalPlainRepository extends AbstractRepository {
	// valid name for the package description XML file
	private static final String PACKAGE_DESCRIPTION_XML = "packagedescription.xml";
	private final File directory;
	
	private String fingerprint;
	
	private Timer timerDaemon;
	
	
	/**
	 * 
	 * Constructor for file system based repositories. Scans all sub-directories of a given sourceDirectory
	 * for valid workspaces and attempt to interpret them as MovingCodePackages.
	 * Incomplete or malformed packages will be ignored.
	 * 
	 * @param sourceDirectory {@link File} - the directory to be scanned for Moving Code Packages.
	 * 
	 */
	public LocalPlainRepository(File sourceDirectory) {
		this.directory = sourceDirectory;
		// compute directory fingerprint
		fingerprint = RepositoryUtils.directoryFingerprint(directory);
		
		// load packages from folder
		load();
		
		// start timer daemon
		timerDaemon = new Timer(true);
		timerDaemon.scheduleAtFixedRate(new CheckFolder(), 0, IMovingCodeRepository.localPollingInterval);
	}
	
	private void load(){
		// recursively obtain all zipfiles in sourceDirectory
		Collection<File> packageFolders = scanForFolders(directory);

		logger.info("Scanning directory: " + directory.getAbsolutePath());
		
		
		for (File currentFolder : packageFolders) {
			
			// attempt to read packageDescription XML
			File packageDescriptionFile = new File(currentFolder, PACKAGE_DESCRIPTION_XML);
			if (!packageDescriptionFile.exists()){
				continue; // skip this and immediately jump to the next iteration
			}
			
			PackageDescriptionDocument pd;
			try {
				pd = PackageDescriptionDocument.Factory.parse(packageDescriptionFile);
			} catch (XmlException e) {
				// silently skip this and immediately jump to the next iteration
				continue;
			} catch (IOException e) {
				// silently skip this and immediately jump to the next iteration
				continue;
			}
			
			// attempt to access workspace root folder
			String workspace = pd.getPackageDescription().getWorkspace().getWorkspaceRoot();
			if (workspace.startsWith("./")){
				workspace = workspace.substring(2); // remove leading "./" if it exists
			}
			File workspaceDir = new File(currentFolder, workspace);
			if (!workspaceDir.exists()){
				continue; // skip this and immediately jump to the next iteration
			}
			
			// guess timestamp
			Date timestamp = new Date(lastFileModified(currentFolder));
			
			// packageID = absolute path
			String id = currentFolder.getPath();
			
			logger.info("Found package: " + currentFolder + "; using ID: " + id);

			MovingCodePackage mcPackage = new MovingCodePackage(workspaceDir, pd, timestamp, id);
			// validate
			// and add to package map
			// and add current file to zipFiles map
			if (mcPackage.isValid()) {
				register(mcPackage);
			}
			else {
				logger.error(currentFolder.getAbsolutePath() + " is an invalid package.");
			}
		}
	}
	
	/**
	 * Scans a directory for subfolders (i.e. immediate child folders) and adds them
	 * to the resulting Collection.
	 * 
	 * @param directory {@link File} - parent directory to scan.
	 * @return {@link Collection} of {@link File} - the directories found
	 */
	private static Collection<File> scanForFolders(File directory) {
		return FileUtils.listFiles(directory, FileFilterUtils.directoryFileFilter(), null);
	}

	/**
	 * Finds the last modified date of a directory by scanning it's contents
	 * 
	 * TODO: does this also check the modification date of directories?
	 * 
	 * @param directory {@link File} - the directory to be scanned
	 * @return 
	 */
	private static long lastFileModified(File directory) {
		// recursively find all files in subdirectory 
		Collection<File> files = FileUtils.listFiles(directory, null, true);
		
		// initialize with modification date of the directory argument
		long lastMod = directory.lastModified(); 
		
		for (File file : files) {
			if (file.lastModified() > lastMod) {
				lastMod = file.lastModified();
			}
		}
		return lastMod;
	}
	
	/**
	 * A task which re-computes the directory's fingerprint and
	 * triggers a content reload if required.
	 * 
	 * @author Matthias Mueller
	 *
	 */
	private final class CheckFolder extends TimerTask {
		
		@Override
		public void run() {
			String newFingerprint = RepositoryUtils.directoryFingerprint(directory);
			if (!newFingerprint.equals(fingerprint)){
				logger.info("Repository content has silently changed. Running update ...");
				// set new fingerprint
				fingerprint = newFingerprint;
				// clear an reload
				clear();
				load();
				
				logger.info("Reload finished. Calling Repository Change Listeners.");
				informRepositoryChangeListeners();
			}			
		}
	}
}