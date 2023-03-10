/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.user;

import static android.car.test.util.UserTestingHelper.getDefaultUserType;
import static android.car.test.util.UserTestingHelper.newGuestUser;
import static android.car.test.util.UserTestingHelper.newSecondaryUser;

import static com.android.car.user.MockedUserHandleBuilder.expectPreCreatedGuestUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectPreCreatedRegularUserExists;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.SyncAnswer;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.car.internal.os.CarSystemProperties;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

public final class UserPreCreatorTest extends AbstractExtendedMockitoTestCase {

    private static final int USER_MANAGER_TIMEOUT_MS = 100;
    private static final int ADDITIONAL_TIME_MS = 200;
    private static final int PRE_CREATED_USER_ID = 24;
    private static final int PRE_CREATED_GUEST_ID = 25;

    @Mock
    private UserManager mUserManager;
    @Mock
    private Context mContext;
    @Mock
    private UserHandleHelper mUserHandleHelper;

    private UserPreCreator mUserPreCreator;

    public UserPreCreatorTest() {
        super(UserPreCreator.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(CarSystemProperties.class);
    }

    @Before
    public void setUpMocks() {
        mUserPreCreator = spy(new UserPreCreator(mContext, mUserManager, mUserHandleHelper));
    }

    @Test
    public void testPreCreatedUsersLessThanRequested() throws Exception {
        // Set existing user
        expectNoPreCreatedUser();
        // Set number of requested user
        setNumberRequestedUsersProperty(1);
        setNumberRequestedGuestsProperty(0);
        SyncAnswer<UserInfo> syncUserInfo = mockPreCreateUser(/* isGuest= */ false);

        mUserPreCreator.managePreCreatedUsers();
        syncUserInfo.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserCreated(/* isGuest= */ false);
    }

    @Test
    public void testPreCreatedGuestsLessThanRequested() throws Exception {
        // Set existing user
        expectNoPreCreatedUser();
        // Set number of requested user
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(1);
        SyncAnswer<UserInfo> syncUserInfo = mockPreCreateUser(/* isGuest= */ true);

        mUserPreCreator.managePreCreatedUsers();
        syncUserInfo.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserCreated(/* isGuest= */ true);
    }

    @Test
    public void testRemovePreCreatedUser() throws Exception {
        UserHandle user = expectPreCreatedUser(/* isGuest= */ false, /* isInitialized= */ true);
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(0);

        SyncAnswer<Boolean> syncRemoveStatus = mockRemoveUser(PRE_CREATED_USER_ID);

        mUserPreCreator.managePreCreatedUsers();
        syncRemoveStatus.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserRemoved(user);
    }

    @Test
    public void testRemovePreCreatedGuest() throws Exception {
        UserHandle user = expectPreCreatedUser(/* isGuest= */ true, /* isInitialized= */ true);
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(0);
        SyncAnswer<Boolean>  syncRemoveStatus = mockRemoveUser(PRE_CREATED_GUEST_ID);

        mUserPreCreator.managePreCreatedUsers();
        syncRemoveStatus.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserRemoved(user);
    }

    @Test
    public void testRemoveInvalidPreCreatedUser() throws Exception {
        UserHandle user = expectPreCreatedUser(/* isGuest= */ false, /* isInitialized= */ false);
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(0);
        SyncAnswer<Boolean>  syncRemoveStatus = mockRemoveUser(PRE_CREATED_USER_ID);

        mUserPreCreator.managePreCreatedUsers();
        syncRemoveStatus.await(ADDITIONAL_TIME_MS);

        verifyUserRemoved(user);
    }

    @Test
    public void testManagePreCreatedUsersDoNothing() throws Exception {
        expectPreCreatedUser(/* isGuest= */ false, /* isInitialized= */ true);
        setNumberRequestedUsersProperty(1);
        setNumberRequestedGuestsProperty(0);
        mockPreCreateUser(/* isGuest= */ false);
        mockRemoveUser(PRE_CREATED_USER_ID);

        mUserPreCreator.managePreCreatedUsers();

        verifyPostPreCreatedUserSkipped();
    }

    @Test
    public void testPreCreateUserExceptionLogged() throws Exception {
        mockPreCreateUserException();
        mUserPreCreator.preCreateUsers(false);

        verifyPostPreCreatedUserException();
    }

    private SyncAnswer<UserInfo> mockPreCreateUserException() {
        SyncAnswer<UserInfo> syncException = SyncAnswer.forException(new Exception());
        when(mUserManager.preCreateUser(anyString()))
                .thenAnswer(syncException);
        return syncException;
    }

    private void verifyUserCreated(boolean isGuest) throws Exception {
        String userType =
                isGuest ? UserManager.USER_TYPE_FULL_GUEST : UserManager.USER_TYPE_FULL_SECONDARY;
        verify(mUserManager).preCreateUser(eq(userType));
    }

    private void verifyUserRemoved(UserHandle user) throws Exception {
        verify(mUserManager).removeUser(user);
    }

    private void verifyPostPreCreatedUserSkipped() throws Exception {
        verify(mUserManager, never()).preCreateUser(any());
    }

    private void verifyPostPreCreatedUserException() throws Exception {
        verify(mUserPreCreator).logPrecreationFailure(anyString(), any());
    }

    private void setNumberRequestedUsersProperty(int numberUser) {
        doReturn(Optional.of(numberUser)).when(
                () -> CarSystemProperties.getNumberPreCreatedUsers());
    }

    private void setNumberRequestedGuestsProperty(int numberGuest) {
        doReturn(Optional.of(numberGuest)).when(
                () -> CarSystemProperties.getNumberPreCreatedGuests());
    }

    private SyncAnswer<UserInfo> mockPreCreateUser(boolean isGuest) {
        UserInfo newUser = isGuest ? newGuestUser(PRE_CREATED_GUEST_ID) :
                newSecondaryUser(PRE_CREATED_USER_ID);
        SyncAnswer<UserInfo> syncUserInfo = SyncAnswer.forReturn(newUser);
        when(mUserManager.preCreateUser(getDefaultUserType(isGuest)))
                .thenAnswer(syncUserInfo);

        return syncUserInfo;
    }

    private SyncAnswer<Boolean> mockRemoveUser(@UserIdInt int userId) {
        SyncAnswer<Boolean> syncRemoveStatus = SyncAnswer.forReturn(true);
        when(mUserManager.removeUser(UserHandle.of(userId))).thenAnswer(syncRemoveStatus);

        return syncRemoveStatus;
    }

    private void expectNoPreCreatedUser() throws Exception {
        when(mUserHandleHelper.getUserHandles(/* excludePartial= */ true, /* excludeDying= */ true,
                /* excludePreCreated= */ false)).thenReturn(new ArrayList<UserHandle>());
    }

    private UserHandle expectPreCreatedUser(boolean isGuest, boolean isInitialized) {
        int userId = isGuest ? PRE_CREATED_GUEST_ID : PRE_CREATED_USER_ID;
        UserHandle user;
        if (isGuest) {
            user = expectPreCreatedGuestUserExists(mUserHandleHelper, userId, isInitialized);
        } else {
            user = expectPreCreatedRegularUserExists(mUserHandleHelper, userId, isInitialized);
        }
        when(mUserHandleHelper.getUserHandles(/* excludePartial= */ true, /* excludeDying= */ true,
                /* excludePreCreated= */ false)).thenReturn(Arrays.asList(user));
        return user;
    }
}
