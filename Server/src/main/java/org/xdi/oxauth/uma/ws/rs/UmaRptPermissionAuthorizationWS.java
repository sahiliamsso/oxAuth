/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.uma.ws.rs;

import com.wordnik.swagger.annotations.Api;
import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.slf4j.Logger;
import org.xdi.oxauth.model.common.AuthorizationGrant;
import org.xdi.oxauth.model.common.uma.UmaRPT;
import org.xdi.oxauth.model.error.ErrorResponseFactory;
import org.xdi.oxauth.model.uma.RptAuthorizationRequest;
import org.xdi.oxauth.model.uma.RptAuthorizationResponse;
import org.xdi.oxauth.model.uma.UmaConstants;
import org.xdi.oxauth.model.uma.UmaErrorResponseType;
import org.xdi.oxauth.model.uma.persistence.UmaPermission;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.ClientService;
import org.xdi.oxauth.service.uma.UmaPermissionManager;
import org.xdi.oxauth.service.uma.UmaRptManager;
import org.xdi.oxauth.service.uma.UmaValidationService;
import org.xdi.oxauth.service.uma.authorization.AuthorizationService;
import org.xdi.oxauth.util.ServerUtil;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * The endpoint at which the requester asks for authorization to have a new permission.
 */
@Path("/requester/perm")
@Api(value = "/requester/perm", description = "RPT authorization endpoint. RPT is authorized with new permission(s).")
public class UmaRptPermissionAuthorizationWS {

    @Inject
    private Logger log;

    @Inject
    private ErrorResponseFactory errorResponseFactory;

    @Inject
    private UmaRptManager rptManager;

    @Inject
    private UmaPermissionManager permissionManager;

    @Inject
    private UmaValidationService umaValidationService;

    @Inject
    private AuthorizationService umaAuthorizationService;

    @Inject
    private ClientService clientService;

    @Inject
    private LdapEntryManager ldapEntryManager;

    @POST
    @Consumes({UmaConstants.JSON_MEDIA_TYPE})
    @Produces({UmaConstants.JSON_MEDIA_TYPE})
    public Response requestRptPermissionAuthorization(
            @HeaderParam("Authorization") String authorization,
            RptAuthorizationRequest rptAuthorizationRequest,
            @Context HttpServletRequest httpRequest) {
        try {
            // todo uma2 schedule for remove ?
            final AuthorizationGrant grant = null;//umaValidationService.assertHasAuthorizationScope(authorization);

            final UmaRPT rpt = authorizeRptPermission(authorization, rptAuthorizationRequest, httpRequest, grant);

            // convert manually to avoid possible conflict between resteasy providers, e.g. jettison, jackson
            return Response.ok(ServerUtil.asJson(new RptAuthorizationResponse(rpt.getCode()))).build();
        } catch (Exception ex) {
            log.error("Exception happened", ex);
            if (ex instanceof WebApplicationException) {
                throw (WebApplicationException) ex;
            }

            throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponseFactory.getUmaJsonErrorResponse(UmaErrorResponseType.SERVER_ERROR)).build());
        }
    }

    private UmaRPT authorizeRptPermission(String authorization,
                                          RptAuthorizationRequest rptAuthorizationRequest,
                                          HttpServletRequest httpRequest,
                                          AuthorizationGrant grant) {
        UmaRPT rpt;
        if (Util.isNullOrEmpty(rptAuthorizationRequest.getRpt())) {
            rpt = rptManager.createRPT(authorization);
        } else {
            rpt = rptManager.getRPTByCode(rptAuthorizationRequest.getRpt());
        }

        // Validate RPT
        try {
            umaValidationService.validateRPT(rpt);
        } catch (WebApplicationException e) {
            // according to latest UMA spec ( dated 2015-02-23 https://docs.kantarainitiative.org/uma/draft-uma-core.html)
            // it's up to implementation whether to create new RPT for each request or pass back requests RPT.
            // Here we decided to pass back new RPT if request's RPT in invalid.
            rpt = rptManager.getRPTByCode(rptAuthorizationRequest.getRpt());
        }

        final List<UmaPermission> permissions = permissionManager.getPermissionByTicket(rptAuthorizationRequest.getTicket());

        umaValidationService.validatePermissions(permissions);

        boolean allowToAdd = true;
        for (UmaPermission permission : permissions) {
            if (!umaAuthorizationService.allowToAddPermission(grant, rpt, permission, httpRequest, rptAuthorizationRequest.getClaims())) {
                allowToAdd = false;
                break;
            }
        }

        if (allowToAdd) {
            for (UmaPermission permission : permissions) {
                rptManager.addPermissionToRPT(rpt, permission);
                invalidateTicket(permission);
            }
            return rpt;
        }

        // throw not authorized exception
        throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                .entity(errorResponseFactory.getUmaJsonErrorResponse(UmaErrorResponseType.NOT_AUTHORIZED_PERMISSION)).build());
    }

    private void invalidateTicket(UmaPermission permission) {
        try {
            permission.setStatus("invalidated"); // invalidate ticket and persist
            ldapEntryManager.merge(permission);
        } catch (Exception e) {
            log.error("Failed to invalidate ticket: " + permission.getTicket() + ". " + e.getMessage(), e);
        }
    }
}