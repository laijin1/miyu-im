package com.familychat.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.familychat.app.feature_chat.R;

/**
 * Thin Fragment that wraps activity_chat.xml layout.
 * All logic stays in ChatActivity — this just provides the view.
 */
public class ChatPageFragment extends Fragment {

    public View rootView;
    public TextView tvChatName, tvChatStatus;
    public ImageButton btnBack, btnPlus, btnVoiceToggle, btnVoiceCall, btnVideoCall;
    public ImageButton btnSend;
    public EditText etMessage;
    public TextView btnHoldTalk;
    public androidx.recyclerview.widget.RecyclerView rvMessages;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        rootView = inflater.inflate(R.layout.activity_chat, container, false);

        tvChatName = rootView.findViewById(R.id.tvChatName);
        tvChatStatus = rootView.findViewById(R.id.tvChatStatus);
        btnBack = rootView.findViewById(R.id.btnBack);
        btnPlus = rootView.findViewById(R.id.btnPlus);
        btnVoiceToggle = rootView.findViewById(R.id.btnVoiceToggle);
        btnVoiceCall = rootView.findViewById(R.id.btnVoiceCall);
        btnVideoCall = rootView.findViewById(R.id.btnVideoCall);
        btnSend = rootView.findViewById(R.id.btnSend);
        etMessage = rootView.findViewById(R.id.etMessage);
        btnHoldTalk = rootView.findViewById(R.id.btnHoldTalk);
        rvMessages = rootView.findViewById(R.id.rvMessages);

        return rootView;
    }
}
