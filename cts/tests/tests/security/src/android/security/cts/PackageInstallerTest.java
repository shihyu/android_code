/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.cts;

import android.Manifest;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.AsbSecurityTest;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;
import com.android.sts.common.util.StsExtraBusinessLogicTestCase;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@AppModeFull
public class PackageInstallerTest extends StsExtraBusinessLogicTestCase {

    private static final String TEST_APP_NAME = "android.security.cts.packageinstallertestapp";

    private static final TestApp TEST_APP = new TestApp(
            "PackageInstallerTestApp", TEST_APP_NAME, 1, /*isApex*/ false,
            "PackageInstallerTestApp.apk");

    @Before
    public void setUp() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES,
                        Manifest.permission.DELETE_PACKAGES,
                        Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                        Manifest.permission.BIND_PACKAGE_VERIFIER);
    }

    @After
    public void tearDown() throws Exception {
        Uninstall.packages(TestApp.A);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    @AsbSecurityTest(cveBugId = 138650665)
    public void verificationCanNotBeDisabledByInstaller() throws Exception {
        Install.single(TEST_APP).addInstallFlags(
                0x00080000 /* PackageManager.INSTALL_DISABLE_VERIFICATION */).commit();
        String packageName = PackageVerificationsBroadcastReceiver.packages.poll(30,
                TimeUnit.SECONDS);
        Assert.assertNotNull("Did not receive broadcast", packageName);
        Assert.assertEquals(TEST_APP_NAME, packageName);
    }
}
