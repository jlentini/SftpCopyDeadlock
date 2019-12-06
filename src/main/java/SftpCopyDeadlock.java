/*
 * Copyright (c) 2019 by Delphix. All rights reserved.
 *
 * Simple program to reproduce https://issues.apache.org/jira/browse/VFS-627
 */

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.Selectors;
import org.apache.commons.vfs2.auth.StaticUserAuthenticator;
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;

public class SftpCopyDeadlock {

    public static void main(String[] args) throws FileSystemException {
        if (6 != args.length) {
            System.out.printf("Usage: %s <useWorkaround> <user> <password> <host> <fromFile> <toFile>%n", SftpCopyDeadlock.class.getSimpleName());
            System.exit(-1);
        }

		boolean useWorkaround = "yes".equalsIgnoreCase(args[0]);
        String userName = args[1];
        String password = args[2];
        String host = args[3];
        String fromFileUrl = String.format("sftp://@%s:22/%s", host, args[4]);
        String toFileUrl = String.format("sftp://@%s:22/%s", host, args[5]);

        try (StandardFileSystemManager fromFsManager = getFileSystemManager();
			 StandardFileSystemManager toFsManager = (useWorkaround ? getFileSystemManager() : fromFsManager) ) {

            FileSystemOptions opts = getFileSystemOptions(userName, password);

			System.out.printf("Number of File System Managers: %d%n", fromFsManager == toFsManager ? 1 : 2);
            System.out.println("Replacing contents of " + toFileUrl + " with " + fromFileUrl);

            FileObject toFile = toFsManager.resolveFile(toFileUrl, opts);
            FileObject fromFile = fromFsManager.resolveFile(fromFileUrl, opts);

            toFile.copyFrom(fromFile, Selectors.SELECT_SELF);
            System.out.println("Copy complete from " + fromFileUrl + " to " + toFileUrl);
        }
    }

    /**********************************************************************************************
     *
     * Utility Functions
     *
     *********************************************************************************************/

    private static StandardFileSystemManager getFileSystemManager() throws FileSystemException {
        StandardFileSystemManager fsManager = new StandardFileSystemManager();
        fsManager.init();
        return fsManager;
    }

    private static FileSystemOptions getFileSystemOptions(String userName, String password)
            throws FileSystemException {
        FileSystemOptions opts = new FileSystemOptions();
        SftpFileSystemConfigBuilder.getInstance().setStrictHostKeyChecking(opts, "no");
        SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(opts, false);

        StaticUserAuthenticator auth = new StaticUserAuthenticator(null, userName, password);
        DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(opts, auth);

        return opts;
    }
}
