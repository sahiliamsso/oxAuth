/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service.uma;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.util.StaticUtils;
import org.gluu.site.ldap.persistence.BatchOperation;
import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.slf4j.Logger;
import org.xdi.ldap.model.SearchScope;
import org.xdi.ldap.model.SimpleBranch;
import org.xdi.oxauth.model.common.AccessToken;
import org.xdi.oxauth.model.common.AuthorizationGrantList;
import org.xdi.oxauth.model.common.IAuthorizationGrant;
import org.xdi.oxauth.model.common.uma.UmaRPT;
import org.xdi.oxauth.model.config.StaticConfiguration;
import org.xdi.oxauth.model.uma.persistence.UmaPermission;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.CleanerTimer;
import org.xdi.oxauth.service.token.TokenService;
import org.xdi.util.INumGenerator;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * RPT manager component
 *
 * @author Yuriy Zabrovarnyy Date: 10/16/2012
 */
@Stateless
@Named
public class UmaRptManager {

    private static final String ORGUNIT_OF_RPT = "uma_rpt";

    @Inject
    private Logger log;

    @Inject
    private LdapEntryManager ldapEntryManager;

    @Inject
    private TokenService tokenService;

    @Inject
    private AuthorizationGrantList authorizationGrantList;

    @Inject
    private StaticConfiguration staticConfiguration;

    public static String getDn(String clientDn, String uniqueIdentifier) {
        return String.format("uniqueIdentifier=%s,%s", uniqueIdentifier, branchDn(clientDn));
    }

    public static String branchDn(String clientDn) {
        return String.format("ou=%s,%s", ORGUNIT_OF_RPT, clientDn);
    }

    public void addRPT(UmaRPT p_rpt, String p_clientDn) {
        try {
            addBranchIfNeeded(p_clientDn);
            String id = UUID.randomUUID().toString();
            p_rpt.setId(id);
            p_rpt.setDn(getDn(p_clientDn, id));
            ldapEntryManager.persist(p_rpt);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public UmaRPT getRPTByCode(String rptCode) {
        try {
            final Filter filter = Filter.create(String.format("&(oxAuthTokenCode=%s)", rptCode));
            final String baseDn = staticConfiguration.getBaseDn().getClients();
            final List<UmaRPT> entries = ldapEntryManager.findEntries(baseDn, UmaRPT.class, filter);
            if (entries != null && !entries.isEmpty()) {
                return entries.get(0);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public void deleteRPT(String rptCode) {
        try {
            final UmaRPT t = getRPTByCode(rptCode);
            if (t != null) {
                ldapEntryManager.remove(t);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void cleanupRPTs(final Date now) {
        BatchOperation<UmaRPT> rptBatchService = new BatchOperation<UmaRPT>(ldapEntryManager) {
            @Override
            protected List<UmaRPT> getChunkOrNull(int chunkSize) {
                return ldapEntryManager.findEntries(staticConfiguration.getBaseDn().getClients(), UmaRPT.class, getFilter(), SearchScope.SUB, null, this, 0, chunkSize, chunkSize);
            }

            @Override
            protected void performAction(List<UmaRPT> entries) {
                for (UmaRPT p : entries) {
                    try {
                        ldapEntryManager.remove(p);
                    } catch (Exception e) {
                        log.error("Failed to remove entry", e);
                    }
                }
            }

            private Filter getFilter() {
                try {
                    return Filter.create(String.format("(oxAuthExpiration<=%s)", StaticUtils.encodeGeneralizedTime(now)));
                }catch (LDAPException e) {
                    log.trace(e.getMessage(), e);
                    return Filter.createPresenceFilter("oxAuthExpiration");
                }
            }
        };
        rptBatchService.iterateAllByChunks(CleanerTimer.BATCH_SIZE);
    }

    public void addPermissionToRPT(UmaRPT p_rpt, UmaPermission p_permission) {
        final List<String> permissions = new ArrayList<String>();
        if (p_rpt.getPermissions() != null) {
            permissions.addAll(p_rpt.getPermissions());
        }
        permissions.add(p_permission.getDn());
        p_rpt.setPermissions(permissions);

        try {
            ldapEntryManager.merge(p_rpt);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public List<UmaPermission> getRptPermissions(UmaRPT p_rpt) {
        final List<UmaPermission> result = new ArrayList<UmaPermission>();
        try {
            if (p_rpt != null && p_rpt.getPermissions() != null) {
                final List<String> permissionDns = p_rpt.getPermissions();
                for (String permissionDn : permissionDns) {
                    final UmaPermission permissionObject = ldapEntryManager.find(UmaPermission.class, permissionDn);
                    if (permissionObject != null) {
                        result.add(permissionObject);
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public UmaRPT createRPT(String authorization) {
        String aatToken = tokenService.getTokenFromAuthorizationParameter(authorization);
        IAuthorizationGrant authorizationGrant = authorizationGrantList.getAuthorizationGrantByAccessToken(aatToken);

        UmaRPT rpt = createRPT(authorizationGrant, aatToken);

        addRPT(rpt, authorizationGrant.getClientDn());
        return rpt;
    }

    public UmaRPT createRPT(IAuthorizationGrant grant, String aat) {
        final AccessToken accessToken = (AccessToken) grant.getAccessToken(aat);

        try {
            String code = UUID.randomUUID().toString() + "/" + INumGenerator.generate(8);
            return new UmaRPT(code, new Date(), accessToken.getExpirationDate(), grant.getUserId(), grant.getClientId());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException("Failed to generate RPT, aat: " + aat, e);
        }
    }

    public UmaPermission getPermissionFromRPTByResourceId(UmaRPT p_rpt, String resourceId) {
        try {
            if (p_rpt != null && p_rpt.getPermissions() != null && Util.allNotBlank(resourceId)) {
                final List<String> permissionDns = p_rpt.getPermissions();
                for (String permissionDn : permissionDns) {
                    final UmaPermission permissionObject = ldapEntryManager.find(UmaPermission.class, permissionDn);
                    if (permissionObject != null && resourceId.equals(permissionObject.getResourceId())) {
                        return permissionObject;
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public void addBranch(String clientDn) {
        final SimpleBranch branch = new SimpleBranch();
        branch.setOrganizationalUnitName(ORGUNIT_OF_RPT);
        branch.setDn(branchDn(clientDn));
        ldapEntryManager.persist(branch);
    }

    public void addBranchIfNeeded(String clientDn) {
        if (!containsBranch(clientDn)) {
            addBranch(clientDn);
        }
    }

    public boolean containsBranch(String clientDn) {
        return ldapEntryManager.contains(SimpleBranch.class, branchDn(clientDn));
    }

}