/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen;

import com.google.protobuf.DescriptorProtos;
import org.ballerinalang.net.grpc.exception.BalGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalFileGenerationUtils.generateDescriptor;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalFileGenerationUtils.getDescriptorPath;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalFileGenerationUtils.isWindows;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalFileGenerationUtils.resolveProtoFloderPath;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalFileGenerationUtils.createMetaFolder;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalFileGenerationUtils.resolveProtoFolderPath;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalGenerationConstants.META_LOCATION;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalGenerationConstants.PROTO_SUFFIX;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalGenerationConstants.DESC_SUFFIX;
import static org.wso2.apimgt.gateway.cli.utils.grpc.GrpcGen.BalGenerationConstants.GOOGLE_STANDARD_LIB;

/**
 * Class for generate file descriptors for proto files.
 */
public class DescriptorsGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(DescriptorsGenerator.class);
    public static final String TMP_DIRECTORY_PATH = System.getProperty("java.io.tmpdir");
    private static final CharSequence EMPTY_STRING = "";

    public static Set<byte[]> generateDependentDescriptor(String exePath, String rootProtoPath, String
            rootDescriptorPath) {
        Set<byte[]> dependentDescSet = new HashSet<>();
        File tempDir = new File(TMP_DIRECTORY_PATH);
        File initialFile = new File(rootDescriptorPath);
        try (InputStream targetStream = new FileInputStream(initialFile)) {
            DescriptorProtos.FileDescriptorSet descSet = DescriptorProtos.FileDescriptorSet.parseFrom(targetStream);
            for (String dependentFilePath : descSet.getFile(0).getDependencyList()) {
                if (isWindows()) {
                    dependentFilePath = dependentFilePath.replaceAll("/", "\\\\");
                }
                // desc file path: desc_gen/dependencies + <filename>.desc
                String relativeDescFilepath = BalGenerationConstants.META_DEPENDENCY_LOCATION + dependentFilePath
                        .substring(dependentFilePath.lastIndexOf(BalGenerationConstants.FILE_SEPARATOR),
                                dependentFilePath.length()).replace(PROTO_SUFFIX, DESC_SUFFIX);

                File dependentDescFile = new File(tempDir, relativeDescFilepath);
                boolean isDirectoryCreated = dependentDescFile.getParentFile().mkdirs();
                if (!isDirectoryCreated) {
                    LOG.debug("Parent directories didn't create for the file '" + relativeDescFilepath);
                }
                //Derive proto file path of the dependent library.
                String protoPath;
                String protoFolderPath;
                if (!dependentFilePath.contains(GOOGLE_STANDARD_LIB)) {
                    protoPath = new File(resolveProtoFolderPath(rootProtoPath), dependentFilePath).getAbsolutePath();
                    protoFolderPath = resolveProtoFolderPath(rootProtoPath);
                } else {
                    protoPath = new File(tempDir, dependentFilePath).getAbsolutePath();
                    protoFolderPath = tempDir.getAbsolutePath();
                }

                String command = new ProtocCommandBuilder(exePath, protoPath, protoFolderPath, dependentDescFile
                        .getAbsolutePath()).build();
                generateDescriptor(command);
                File childFile = new File(tempDir, relativeDescFilepath);
                try (InputStream childStream = new FileInputStream(childFile)) {
                    DescriptorProtos.FileDescriptorSet childDescSet = DescriptorProtos.FileDescriptorSet
                            .parseFrom(childStream);
                    if (childDescSet.getFile(0).getDependencyCount() != 0) {
                        Set<byte[]> childList = generateDependentDescriptor(exePath, rootProtoPath, childFile
                                .getAbsolutePath());
                        dependentDescSet.addAll(childList);
                    }
                    byte[] dependentDesc = childDescSet.getFile(0).toByteArray();
                    if (dependentDesc.length == 0) {
                        throw new BalGenerationException("Error occurred at generating dependent proto " +
                                "descriptor for dependent proto '" + relativeDescFilepath + "'.");
                    }
                    dependentDescSet.add(dependentDesc);
                } catch (IOException e) {
                    throw new BalGenToolException("Error extracting dependent bal.", e);
                }
            }
        } catch (IOException e) {
            throw new BalGenToolException("Error parsing descriptor file " + initialFile, e);
        }
        return dependentDescSet;
    }
}