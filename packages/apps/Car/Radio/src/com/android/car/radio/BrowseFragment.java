/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.radio;

import static com.android.car.ui.core.CarUi.requireInsets;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.car.broadcastradio.support.Program;
import com.android.car.radio.storage.RadioStorage;
import com.android.car.radio.util.Log;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.baselayout.InsetsChangedListener;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

/**
 * Fragment that shows all browseable radio stations from background scan
 */
public class BrowseFragment extends Fragment implements InsetsChangedListener {
    private static final String TAG = "BcRadioApp.BrwFrg";

    private RadioController mRadioController;
    private BrowseAdapter mBrowseAdapter;
    private RadioStorage mRadioStorage;
    private View mRootView;
    private CarUiRecyclerView mBrowseList;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.browse_fragment, container, false);
        return mRootView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        Context context = getContext();

        mRadioStorage = RadioStorage.getInstance(context);
        mBrowseAdapter = new BrowseAdapter(this, mRadioController.getCurrentProgram(),
                mRadioStorage.getFavorites());

        mBrowseAdapter.setOnItemClickListener(mRadioController::tune);
        mBrowseAdapter.setOnItemFavoriteListener(this::handlePresetItemFavoriteChanged);

        mBrowseList = view.findViewById(R.id.browse_list);
        mBrowseList.setLayoutManager(new LinearLayoutManager(context));
        mBrowseList.setAdapter(mBrowseAdapter);
        mBrowseList.setVerticalFadingEdgeEnabled(true);
        mBrowseList.setFadingEdgeLength(getResources()
                .getDimensionPixelSize(R.dimen.browse_list_fading_edge_length));

        mRadioController.getProgramList().observe(this, mBrowseAdapter::setProgramList);
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (!isVisibleToUser) return;

        try {
            mRadioController.setSkipMode(SkipMode.BROWSE);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't set skip mode", e);
        }
    }

    @Override
    public void onCarUiInsetsChanged(Insets insets) {
        View view = requireView();
        View recyclerView = view.findViewById(R.id.browse_list);
        recyclerView.setPadding(insets.getLeft(),
                insets.getTop(),
                insets.getRight(),
                insets.getBottom());
    }

    @Override
    public void onStart() {
        super.onStart();

        // This is needed to apply the inset changes that happened before this fragment was visible
        onCarUiInsetsChanged(requireInsets(getActivity()));
    }

    private void handlePresetItemFavoriteChanged(Program program, boolean saveAsFavorite) {
        if (saveAsFavorite) {
            mRadioStorage.addFavorite(program);
        } else {
            mRadioStorage.removeFavorite(program.getSelector());
        }
    }

    static BrowseFragment newInstance(RadioController radioController) {
        BrowseFragment fragment = new BrowseFragment();
        fragment.mRadioController = radioController;
        return fragment;
    }
}
