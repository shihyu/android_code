/*
 * Copyright (C) 2020 Google Inc.
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

package com.android.car.carlauncher.homescreen.audio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.View;

import com.android.car.carlauncher.homescreen.HomeCardInterface;
import com.android.car.carlauncher.homescreen.ui.CardHeader;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextView;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextWithControlsView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class HomeAudioCardPresenterTest {

    private static final CardHeader CARD_HEADER = new CardHeader("testAppName", /* appIcon = */
            null);
    private static final DescriptiveTextView CARD_CONTENT = new DescriptiveTextView(/* image = */
            null, "title", "subtitle");

    private HomeAudioCardPresenter mPresenter;

    @Mock
    private View mFragmentView;
    @Mock
    private HomeCardInterface.View mView;
    @Mock
    private HomeCardInterface.Model mModel;
    @Mock
    private InCallModel mOtherModel;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mModel.getCardHeader()).thenReturn(CARD_HEADER);
        when(mModel.getCardContent()).thenReturn(CARD_CONTENT);
        mPresenter = new HomeAudioCardPresenter();
        mPresenter.setView(mView);
    }

    @Test
    public void onModelUpdated_updatesFragment() {
        mPresenter.onModelUpdated(mModel);
        mPresenter.onViewClicked(mFragmentView);

        verify(mView).updateHeaderView(CARD_HEADER);
        verify(mView).updateContentView(CARD_CONTENT);
        verify(mModel).onClick(mFragmentView);
    }

    @Test
    public void onModelUpdated_nullDifferentModel_doesNotUpdate() {
        when(mOtherModel.getCardHeader()).thenReturn(null);
        mPresenter.onModelUpdated(mModel);
        reset(mView);

        mPresenter.onModelUpdated(mOtherModel);
        mPresenter.onViewClicked(mFragmentView);

        verify(mView, never()).hideCard();
        verify(mView, never()).updateHeaderView(any());
        verify(mView, never()).updateContentView(any());
        verify(mModel).onClick(mFragmentView);
        verify(mOtherModel, never()).onClick(any());
    }

    @Test
    public void onModelUpdated_activePhoneCall_doesNotUpdateFragment() {
        //setUpActivePhoneCall in presenter
        CardHeader callModelHeader = new CardHeader("dialer", /* appIcon = */
                null);
        DescriptiveTextWithControlsView callModelContent = new DescriptiveTextWithControlsView(
                /* image = */ null, "callerNumber", "ongoingCall");
        when(mOtherModel.getCardHeader()).thenReturn(callModelHeader);
        when(mOtherModel.getCardContent()).thenReturn(callModelContent);
        mPresenter.onModelUpdated(mOtherModel);

        // send MediaModel update during ongoing call
        mPresenter.onModelUpdated(mModel);

        //verify call
        verify(mView).updateHeaderView(callModelHeader);
        verify(mView).updateContentView(callModelContent);
        verify(mView, never()).hideCard();
        verify(mView, never()).updateHeaderView(CARD_HEADER);
        verify(mView, never()).updateContentView(CARD_CONTENT);
    }

    @Test
    public void onModelUpdated_nullSameModel_updatesFragment() {
        mPresenter.onModelUpdated(mModel);
        reset(mView);
        when(mModel.getCardHeader()).thenReturn(null);

        mPresenter.onModelUpdated(mModel);

        verify(mView).hideCard();
    }

    @Test
    public void onModelUpdated_nullModelAndNullCurrentModel_updatesFragment() {
        when(mModel.getCardHeader()).thenReturn(null);

        mPresenter.onModelUpdated(mModel);

        verify(mView, never()).hideCard();
    }
}
