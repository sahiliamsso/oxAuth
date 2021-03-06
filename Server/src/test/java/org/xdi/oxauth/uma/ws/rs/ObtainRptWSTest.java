/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.uma.ws.rs;

import java.net.URI;

import org.jboss.arquillian.test.api.ArquillianResource;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;
import org.xdi.oxauth.BaseTest;
import org.xdi.oxauth.model.uma.RPTResponse;
import org.xdi.oxauth.model.uma.TUma;
import org.xdi.oxauth.model.uma.UmaTestUtil;
import org.xdi.oxauth.model.uma.wrapper.Token;

/**
 * @author Yuriy Zabrovarnyy
 * @version 0.9, 18/03/2013
 */

public class ObtainRptWSTest extends BaseTest {

	@ArquillianResource
	private URI url;

	private static Token aat;

	@Test
	@Parameters({ "authorizePath", "tokenPath", "umaUserId", "umaUserSecret", "umaAatClientId", "umaAatClientSecret",
			"umaRedirectUri" })
	public void init(String authorizePath, String tokenPath, String umaUserId, String umaUserSecret,
			String umaAatClientId, String umaAatClientSecret, String umaRedirectUri) {
		aat = TUma.requestAat(url, authorizePath, tokenPath, umaUserId, umaUserSecret, umaAatClientId,
				umaAatClientSecret, umaRedirectUri);
	}

	@Test(dependsOnMethods = "init")
	@Parameters({ "umaRptPath", "umaAmHost" })
	public void obtainRpt(String umaRptPath, String umaAmHost) {
		final RPTResponse r = TUma.requestRpt(url, aat, umaRptPath, umaAmHost);
		UmaTestUtil.assert_(r);
	}
}
