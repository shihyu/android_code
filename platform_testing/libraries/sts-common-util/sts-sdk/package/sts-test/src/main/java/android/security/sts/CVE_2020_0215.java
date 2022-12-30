/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.security.sts;

import static com.android.sts.common.CommandUtil.runAndCheck;

import android.platform.test.annotations.AsbSecurityTest;

import com.android.sts.common.tradefed.testtype.StsExtraBusinessLogicHostTestBase;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.runner.RunWith;
import org.junit.Test;

@RunWith(DeviceJUnit4ClassRunner.class)
public class CVE_2020_0215 extends StsExtraBusinessLogicHostTestBase {

    static final String TEST_APP = "CVE_2020_0215.apk";
    static final String TEST_PKG = "android.security.sts.CVE_2020_0215";
    static final String TEST_CLASS = TEST_PKG + "." + "DeviceTest";

    /** b/140417248 */
    @AsbSecurityTest(cveBugId = 140417248)
    @Test
    public void testPocCVE_2020_0215() throws Exception {
        ITestDevice device = getDevice();
        device.enableAdbRoot();
        uninstallPackage(device, TEST_PKG);

        runAndCheck(device, "input keyevent KEYCODE_WAKEUP");
        runAndCheck(device, "input keyevent KEYCODE_MENU");
        runAndCheck(device, "input keyevent KEYCODE_HOME");

        installPackage(TEST_APP);
        runDeviceTests(TEST_PKG, TEST_CLASS, "testCVE_2020_0215");
    }
}
