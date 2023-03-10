#!/usr/bin/env python
#
# Copyright 2018 - The Android Open Source Project
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
"""Common code used by acloud create methods/classes."""

import collections
import logging
import os
import re
import shutil

from acloud import errors
from acloud.internal import constants
from acloud.internal.lib import android_build_client
from acloud.internal.lib import auth
from acloud.internal.lib import utils


logger = logging.getLogger(__name__)

# Store the file path to upload to the remote instance.
ExtraFile = collections.namedtuple("ExtraFile", ["source", "target"])


def ParseExtraFilesArgs(files_info, path_separator=","):
    """Parse extra-files argument.

    e.g.
    ["local_path,gce_path"]
    -> ExtraFile(source='local_path', target='gce_path')

    Args:
        files_info: List of strings to be converted to namedtuple ExtraFile.
        item_separator: String character to separate file info.

    Returns:
        A list of namedtuple ExtraFile.

    Raises:
        error.MalformedDictStringError: If files_info is malformed.
    """
    extra_files = []
    if files_info:
        for file_info in files_info:
            if path_separator not in file_info:
                raise errors.MalformedDictStringError(
                    "Expecting '%s' in '%s'." % (path_separator, file_info))
            source, target = file_info.split(path_separator)
            extra_files.append(ExtraFile(source, target))
    return extra_files


def ParseKeyValuePairArgs(dict_str, item_separator=",", key_value_separator=":"):
    """Helper function to initialize a dict object from string.

    e.g.
    cpu:2,dpi:240,resolution:1280x800
    -> {"cpu":"2", "dpi":"240", "resolution":"1280x800"}

    Args:
        dict_str: A String to be converted to dict object.
        item_separator: String character to separate items.
        key_value_separator: String character to separate key and value.

    Returns:
        Dict created from key:val pairs in dict_str.

    Raises:
        error.MalformedDictStringError: If dict_str is malformed.
    """
    args_dict = {}
    if not dict_str:
        return args_dict

    for item in dict_str.split(item_separator):
        if key_value_separator not in item:
            raise errors.MalformedDictStringError(
                "Expecting ':' in '%s' to make a key-val pair" % item)
        key, value = item.split(key_value_separator)
        if not value or not key:
            raise errors.MalformedDictStringError(
                "Missing key or value in %s, expecting form of 'a:b'" % item)
        args_dict[key.strip()] = value.strip()

    return args_dict


def GetNonEmptyEnvVars(*variable_names):
    """Get non-empty environment variables.

    Args:
        variable_names: Strings, the variable names.

    Returns:
        List of strings, the variable values that are defined and not empty.
    """
    return list(filter(None, (os.environ.get(v) for v in variable_names)))


def GetCvdHostPackage(package_path=None):
    """Get cvd host package path.

    Look for the host package in specified path or $ANDROID_HOST_OUT and dist
    dir then verify existence and get cvd host package path.

    Args:
        package_path: String of cvd host package path.

    Return:
        A string, the path to the host package.

    Raises:
        errors.GetCvdLocalHostPackageError: Can't find cvd host package.
    """
    if package_path:
        if os.path.exists(package_path):
            return package_path
        raise errors.GetCvdLocalHostPackageError(
            "The cvd host package path (%s) doesn't exist." % package_path)
    dirs_to_check = GetNonEmptyEnvVars(constants.ENV_ANDROID_SOONG_HOST_OUT,
                                       constants.ENV_ANDROID_HOST_OUT)
    dist_dir = utils.GetDistDir()
    if dist_dir:
        dirs_to_check.append(dist_dir)

    for path in dirs_to_check:
        cvd_host_package = os.path.join(path, constants.CVD_HOST_PACKAGE)
        if os.path.exists(cvd_host_package):
            logger.debug("cvd host package: %s", cvd_host_package)
            return cvd_host_package
    raise errors.GetCvdLocalHostPackageError(
        "Can't find the cvd host package (Try lunching a cuttlefish target"
        " like aosp_cf_x86_64_phone-userdebug and running 'm'): \n%s" %
        '\n'.join(dirs_to_check))


def FindLocalImage(path, default_name_pattern, raise_error=True):
    """Find an image file in the given path.

    Args:
        path: The path to the file or the parent directory.
        default_name_pattern: A regex string, the file to look for if the path
                              is a directory.

    Returns:
        The absolute path to the image file.

    Raises:
        errors.GetLocalImageError if this method cannot find exactly one image.
    """
    path = os.path.abspath(path)
    if os.path.isdir(path):
        names = [name for name in os.listdir(path) if
                 re.fullmatch(default_name_pattern, name)]
        if not names:
            if raise_error:
                raise errors.GetLocalImageError("No image in %s." % path)
            return None
        if len(names) != 1:
            raise errors.GetLocalImageError("More than one image in %s: %s" %
                                            (path, " ".join(names)))
        path = os.path.join(path, names[0])
    if os.path.isfile(path):
        return path
    raise errors.GetLocalImageError("%s is not a file." % path)


def DownloadRemoteArtifact(cfg, build_target, build_id, artifact, extract_path,
                           decompress=False):
    """Download remote artifact.

    Args:
        cfg: An AcloudConfig instance.
        build_target: String, the build target, e.g. cf_x86_phone-userdebug.
        build_id: String, Build id, e.g. "2263051", "P2804227"
        artifact: String, zip image or cvd host package artifact.
        extract_path: String, a path include extracted files.
        decompress: Boolean, if true decompress the artifact.
    """
    build_client = android_build_client.AndroidBuildClient(
        auth.CreateCredentials(cfg))
    temp_file = os.path.join(extract_path, artifact)
    build_client.DownloadArtifact(
        build_target,
        build_id,
        artifact,
        temp_file)
    if decompress:
        utils.Decompress(temp_file, extract_path)
        try:
            os.remove(temp_file)
            logger.debug("Deleted temporary file %s", temp_file)
        except OSError as e:
            logger.error("Failed to delete temporary file: %s", str(e))


def PrepareLocalInstanceDir(instance_dir, avd_spec):
    """Create a directory for a local cuttlefish or goldfish instance.

    If avd_spec has the local instance directory, this method creates a
    symbolic link from instance_dir to the directory. Otherwise, it creates an
    empty directory at instance_dir.

    Args:
        instance_dir: The absolute path to the default instance directory.
        avd_spec: AVDSpec object that provides the instance directory.
    """
    if os.path.islink(instance_dir):
        os.remove(instance_dir)
    else:
        shutil.rmtree(instance_dir, ignore_errors=True)

    if avd_spec.local_instance_dir:
        abs_instance_dir = os.path.abspath(avd_spec.local_instance_dir)
        if instance_dir != abs_instance_dir:
            os.symlink(abs_instance_dir, instance_dir)
            return
    if not os.path.exists(instance_dir):
        os.makedirs(instance_dir)
