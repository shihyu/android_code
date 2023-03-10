#!/usr/bin/env python3
#
# Copyright 2017, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Unittests for test_runner_handler."""

# pylint: disable=protected-access
# pylint: disable=line-too-long

import os
import tempfile
import unittest

from unittest import mock

import atest_error
import constants
import module_info
import test_runner_handler
import unittest_constants as uc

from metrics import metrics
from test_finders import test_info
from test_runners import test_runner_base as tr_base

FAKE_TR_NAME_A = 'FakeTestRunnerA'
FAKE_TR_NAME_B = 'FakeTestRunnerB'
MISSING_TR_NAME = 'MissingTestRunner'
FAKE_TR_A_REQS = {'fake_tr_A_req1', 'fake_tr_A_req2'}
FAKE_TR_B_REQS = {'fake_tr_B_req1', 'fake_tr_B_req2'}
MODULE_NAME_A = 'ModuleNameA'
MODULE_NAME_A_AGAIN = 'ModuleNameA_AGAIN'
MODULE_NAME_B = 'ModuleNameB'
MODULE_NAME_B_AGAIN = 'ModuleNameB_AGAIN'
MODULE_INFO_A = test_info.TestInfo(MODULE_NAME_A, FAKE_TR_NAME_A, set())
MODULE_INFO_A_AGAIN = test_info.TestInfo(MODULE_NAME_A_AGAIN, FAKE_TR_NAME_A,
                                         set())
MODULE_INFO_B = test_info.TestInfo(MODULE_NAME_B, FAKE_TR_NAME_B, set())
MODULE_INFO_B_AGAIN = test_info.TestInfo(MODULE_NAME_B_AGAIN, FAKE_TR_NAME_B,
                                         set())
BAD_TESTINFO = test_info.TestInfo('bad_name', MISSING_TR_NAME, set())
BUILD_TOP_DIR = tempfile.TemporaryDirectory().name
PRODUCT_OUT_DIR = os.path.join(BUILD_TOP_DIR, 'out/target/product/vsoc_x86_64')

class FakeTestRunnerA(tr_base.TestRunnerBase):
    """Fake test runner A."""

    NAME = FAKE_TR_NAME_A
    EXECUTABLE = 'echo'

    def run_tests(self, test_infos, extra_args, reporter):
        return 0

    def host_env_check(self):
        pass

    def get_test_runner_build_reqs(self):
        return FAKE_TR_A_REQS

    def generate_run_commands(self, test_infos, extra_args, port=None):
        return ['fake command']


class FakeTestRunnerB(FakeTestRunnerA):
    """Fake test runner B."""

    NAME = FAKE_TR_NAME_B

    def run_tests(self, test_infos, extra_args, reporter):
        return 1

    def get_test_runner_build_reqs(self):
        return FAKE_TR_B_REQS


class TestRunnerHandlerUnittests(unittest.TestCase):
    """Unit tests for test_runner_handler.py"""

    _TEST_RUNNERS = {
        FakeTestRunnerA.NAME: FakeTestRunnerA,
        FakeTestRunnerB.NAME: FakeTestRunnerB,
    }

    def setUp(self):
        mock.patch('test_runner_handler._get_test_runners',
                   return_value=self._TEST_RUNNERS).start()

    def tearDown(self):
        mock.patch.stopall()

    def test_group_tests_by_test_runners(self):
        """Test that we properly group tests by test runners."""
        # Happy path testing.
        test_infos = [MODULE_INFO_A, MODULE_INFO_A_AGAIN, MODULE_INFO_B,
                      MODULE_INFO_B_AGAIN]
        want_list = [(FakeTestRunnerA, [MODULE_INFO_A, MODULE_INFO_A_AGAIN]),
                     (FakeTestRunnerB, [MODULE_INFO_B, MODULE_INFO_B_AGAIN])]
        self.assertEqual(
            want_list,
            test_runner_handler.group_tests_by_test_runners(test_infos))

        # Let's make sure we fail as expected.
        self.assertRaises(
            atest_error.UnknownTestRunnerError,
            test_runner_handler.group_tests_by_test_runners, [BAD_TESTINFO])

    def test_get_test_runner_reqs(self):
        """Test that we get all the reqs from the test runners."""
        test_infos = [MODULE_INFO_A, MODULE_INFO_B]
        want_set = FAKE_TR_A_REQS | FAKE_TR_B_REQS
        empty_module_info = None
        self.assertEqual(
            want_set,
            test_runner_handler.get_test_runner_reqs(empty_module_info,
                                                     test_infos))

    @mock.patch.dict('os.environ', {constants.ANDROID_BUILD_TOP:'/',
                                    constants.ANDROID_PRODUCT_OUT:PRODUCT_OUT_DIR})
    @mock.patch.object(metrics, 'RunnerFinishEvent')
    def test_run_all_tests(self, _mock_runner_finish):
        """Test that the return value as we expected."""
        results_dir = ""
        extra_args = {}
        mod_info = module_info.ModuleInfo(
            module_file=os.path.join(uc.TEST_DATA_DIR, uc.JSON_FILE))
        # Tests both run_tests return 0
        test_infos = [MODULE_INFO_A, MODULE_INFO_A_AGAIN]
        self.assertEqual(
            0,
            test_runner_handler.run_all_tests(
                results_dir, test_infos, extra_args, mod_info)[0])
        # Tests both run_tests return 1
        test_infos = [MODULE_INFO_B, MODULE_INFO_B_AGAIN]
        self.assertEqual(
            1,
            test_runner_handler.run_all_tests(
                results_dir, test_infos, extra_args, mod_info)[0])
        # Tests with on run_tests return 0, the other return 1
        test_infos = [MODULE_INFO_A, MODULE_INFO_B]
        self.assertEqual(
            1,
            test_runner_handler.run_all_tests(
                results_dir, test_infos, extra_args, mod_info)[0])

if __name__ == '__main__':
    unittest.main()
