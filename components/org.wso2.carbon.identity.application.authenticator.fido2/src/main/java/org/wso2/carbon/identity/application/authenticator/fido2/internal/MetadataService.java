/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.com).
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.application.authenticator.fido2.internal;

import com.webauthn4j.anchor.TrustAnchorRepository;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.metadata.FidoMDS3MetadataBLOBProvider;
import com.webauthn4j.metadata.LocalFilesMetadataStatementsProvider;
import com.webauthn4j.metadata.MetadataBLOBProvider;
import com.webauthn4j.metadata.anchor.AggregatingTrustAnchorRepository;
import com.webauthn4j.metadata.anchor.MetadataBLOBBasedTrustAnchorRepository;
import com.webauthn4j.metadata.anchor.MetadataStatementsBasedTrustAnchorRepository;
import com.webauthn4j.validator.attestation.trustworthiness.certpath.DefaultCertPathTrustworthinessValidator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authenticator.fido2.exception.FIDO2AuthenticatorServerException;
import org.wso2.carbon.identity.core.util.IdentityConfigParser;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.wso2.carbon.identity.application.authenticator.fido2.util.FIDO2AuthenticatorConstants.FIDO_MDS_ENDPOINTS;
import static org.wso2.carbon.identity.application.authenticator.fido2.util.FIDO2AuthenticatorConstants.FIDO_MDS_ROOT_CERTIFICATE;
import static org.wso2.carbon.identity.application.authenticator.fido2.util.FIDO2AuthenticatorConstants.FIDO_METADATA_STATEMENTS;

/**
 * Helper class for FIDO2 metadata validations.
 */
public class MetadataService {

    private static final Log log = LogFactory.getLog(MetadataService.class);
    private ObjectConverter objectConverter = null;
    private DefaultCertPathTrustworthinessValidator defaultCertPathTrustworthinessValidator = null;
    private static ArrayList<String> mdsEndpoints = null;

    /**
     * Initialize the DefaultCertPathTrustworthinessValidator object needed for webauthn4j mds validations.
     */
    public void initializeDefaultCertPathTrustworthinessValidator() throws FIDO2AuthenticatorServerException {

        objectConverter = new ObjectConverter();
        X509Certificate rootCertificate;
        try {
            rootCertificate = getMDS3RootCertificate();
        } catch (FileNotFoundException | CertificateException e) {
            log.error("Exception in reading the FIDO2 mds root certificate: " + e.getMessage());
            throw new FIDO2AuthenticatorServerException("Exception in reading the FIDO2 mds root certificate", e);
        }

        // Create URL based MDS BLOB provider.
        MetadataBLOBProvider[] fidoMDS3MetdataBLOBProviders = getMDSEndpoints().stream().map(url -> {
            try {
                FidoMDS3MetadataBLOBProvider fidoMDS3MetadataBLOBProvider = new FidoMDS3MetadataBLOBProvider(
                        objectConverter, url, rootCertificate
                );
                // FIDO conformance test env workaround.
                fidoMDS3MetadataBLOBProvider.setRevocationCheckEnabled(false);
                fidoMDS3MetadataBLOBProvider.refresh();
                return fidoMDS3MetadataBLOBProvider;
            } catch (RuntimeException e) {
                log.error("Exception in constructing url based MDS blob provider for " + url
                        + ". Specifying a null provider. Reason: " + e.getMessage());
                return null;
            }
        }).filter(Objects::nonNull).toArray(MetadataBLOBProvider[]::new);

        /**
         * If metadata validation is enabled, URL based MDS initialization will be enforced.
         * Hence will abort the initialization if the BLOB list is empty. Server will try to reinitialize during
         * the next device registration.
         */
        if (fidoMDS3MetdataBLOBProviders.length == 0) {
            if (log.isDebugEnabled()) {
                log.debug("Ended up in an empty url based metadata BLOB providers list. " +
                        "Hence aborting the current initialization.");
            }
            return;
        }

        MetadataBLOBBasedTrustAnchorRepository metadataBLOBBasedTrustAnchorRepository =
                new MetadataBLOBBasedTrustAnchorRepository(fidoMDS3MetdataBLOBProviders);

        // Create local file based MDS provider (Requires to provide metadata from json files).
        MetadataStatementsBasedTrustAnchorRepository metadataStatementsBasedTrustAnchorRepository = null;

        Path mdsDirectory = Paths.get(readMetadataStatementDirectory());
        if (Files.isDirectory(mdsDirectory)) {
            try {
                if (Files.list(mdsDirectory).findAny().isPresent()) {
                    Path[] metadataPaths = Files.list(mdsDirectory).toArray(Path[]::new);

                    LocalFilesMetadataStatementsProvider localFilesMetadataStatementsProvider =
                            new LocalFilesMetadataStatementsProvider(objectConverter, metadataPaths);
                    metadataStatementsBasedTrustAnchorRepository = new MetadataStatementsBasedTrustAnchorRepository(
                            localFilesMetadataStatementsProvider
                    );
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("No metadata statements found in the configured directory.");
                    }
                }
            } catch (IOException e) {
                log.error("Exception in constructing file based MDS blob provider: " + e.getMessage());
            }
        }

        // Construct trust anchor repository.
        TrustAnchorRepository trustAnchorRepository;
        if (metadataStatementsBasedTrustAnchorRepository == null) {
            trustAnchorRepository = metadataBLOBBasedTrustAnchorRepository;
        } else {
            trustAnchorRepository = new AggregatingTrustAnchorRepository(
                    metadataBLOBBasedTrustAnchorRepository,
                    metadataStatementsBasedTrustAnchorRepository
            );
        }

        // Construct certificate trustworthiness validator object.
        defaultCertPathTrustworthinessValidator = new DefaultCertPathTrustworthinessValidator(
                trustAnchorRepository
        );
        defaultCertPathTrustworthinessValidator.setFullChainProhibited(true);
    }

    /**
     * Get the DefaultCertPathTrustworthinessValidator object needed for webauthn4j mds validations.
     *
     * @return DefaultCertPathTrustworthinessValidator
     */
    public DefaultCertPathTrustworthinessValidator getDefaultCertPathTrustworthinessValidator() {

        return defaultCertPathTrustworthinessValidator;
    }

    private X509Certificate getMDS3RootCertificate() throws CertificateException, FileNotFoundException {

        CertificateFactory certificateFactory = CertificateFactory.getInstance("X509");
        FileInputStream fileInputStream = new FileInputStream(readMDSRootCertificatePath());
        return (X509Certificate) certificateFactory.generateCertificate(fileInputStream);
    }

    private ArrayList<String> getMDSEndpoints() {

        if (mdsEndpoints == null) {
            Object value = IdentityConfigParser.getInstance().getConfiguration().get(FIDO_MDS_ENDPOINTS);
            if (value == null) {
                mdsEndpoints = new ArrayList<>();
            } else if (value instanceof ArrayList) {
                mdsEndpoints = (ArrayList) value;
            } else {
                mdsEndpoints = new ArrayList<>(Collections.singletonList((String) value));
            }
        }

        return mdsEndpoints;
    }

    private String readMDSRootCertificatePath() {

        String value = IdentityUtil.getProperty(FIDO_MDS_ROOT_CERTIFICATE);

        if (isNotBlank(value)) {
            return value;
        } else {
            return EMPTY;
        }
    }

    private String readMetadataStatementDirectory() {

        String value = IdentityUtil.getProperty(FIDO_METADATA_STATEMENTS);

        if (isNotBlank(value)) {
            return value;
        } else {
            return EMPTY;
        }
    }
}
