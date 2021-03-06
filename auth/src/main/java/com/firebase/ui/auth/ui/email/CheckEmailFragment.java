package com.firebase.ui.auth.ui.email;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.design.widget.TextInputLayout;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.firebase.ui.auth.R;
import com.firebase.ui.auth.provider.ProviderUtils;
import com.firebase.ui.auth.ui.ExtraConstants;
import com.firebase.ui.auth.ui.FlowParameters;
import com.firebase.ui.auth.ui.FragmentBase;
import com.firebase.ui.auth.ui.ImeHelper;
import com.firebase.ui.auth.ui.User;
import com.firebase.ui.auth.ui.email.fieldvalidators.EmailFieldValidator;
import com.firebase.ui.auth.util.GoogleApiHelper;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.credentials.Credential;
import com.google.android.gms.auth.api.credentials.CredentialPickerConfig;
import com.google.android.gms.auth.api.credentials.HintRequest;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.EmailAuthProvider;

/**
 * Fragment that shows a form with an email field and checks for existing accounts with that
 * email.
 * <p>
 * Host Activities should implement {@link CheckEmailListener}.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class CheckEmailFragment extends FragmentBase implements
        View.OnClickListener,
        ImeHelper.DonePressedListener {

    /**
     * Interface to be implemented by Activities hosting this Fragment.
     */
    interface CheckEmailListener {

        /**
         * Email entered belongs to an existing email user.
         */
        void onExistingEmailUser(User user);

        /**
         * Email entered belongs to an existing IDP user.
         */
        void onExistingIdpUser(User user);

        /**
         * Email entered does not belong to an existing user.
         */
        void onNewUser(User user);

    }

    public static final String TAG = "CheckEmailFragment";

    private static final int RC_HINT = 13;
    private static final int RC_WELCOME_BACK_IDP = 15;
    private static final int RC_SIGN_IN = 16;

    private EditText mEmailEditText;
    private TextInputLayout mEmailLayout;

    private EmailFieldValidator mEmailFieldValidator;
    private CheckEmailListener mListener;

    private Credential mLastCredential;

    public static CheckEmailFragment newInstance(@NonNull FlowParameters flowParameters,
                                                 @Nullable String email) {
        CheckEmailFragment fragment = new CheckEmailFragment();
        Bundle args = new Bundle();
        args.putParcelable(ExtraConstants.EXTRA_FLOW_PARAMS, flowParameters);
        args.putString(ExtraConstants.EXTRA_EMAIL, email);

        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.check_email_layout, container, false);

        // Email field and validator
        mEmailLayout = (TextInputLayout) v.findViewById(R.id.email_layout);
        mEmailEditText = (EditText) v.findViewById(R.id.email);
        mEmailFieldValidator = new EmailFieldValidator(mEmailLayout);
        mEmailLayout.setOnClickListener(this);
        mEmailEditText.setOnClickListener(this);
        checkEmailValid();
        mEmailEditText.addTextChangedListener(textListener);

        // If we press enter on soft-keyboard it simulates finish button click
        mEmailEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE && getView() != null) {
                    onClick(getView().findViewById(R.id.button_next));
                    return true;
                } else {
                    return false;
                }
            }
        });

        ImeHelper.setImeOnDoneListener(mEmailEditText, this);

        // "Next" button
        v.findViewById(R.id.button_next).setOnClickListener(this);

        return v;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Set listener
        if (!(getActivity() instanceof CheckEmailListener)) {
            throw new IllegalStateException("Activity must implement CheckEmailListener");
        }
        mListener = (CheckEmailListener) getActivity();

        if (savedInstanceState != null) {
            return;
        }

        // Check for email
        String email = getArguments().getString(ExtraConstants.EXTRA_EMAIL);
        if (!TextUtils.isEmpty(email)) {
            // Use email passed in
            mEmailEditText.setText(email);
            validateAndProceed();
        } else if (mHelper.getFlowParams().enableHints) {
            // Try SmartLock email autocomplete hint
            showEmailAutoCompleteHint();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(ExtraConstants.HAS_EXISTING_INSTANCE, true);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case RC_HINT:
                if (data != null) {
                    mLastCredential = data.getParcelableExtra(Credential.EXTRA_KEY);
                    if (mLastCredential != null) {
                        // Get the email from the credential
                        mEmailEditText.setText(mLastCredential.getId());

                        // Attempt to proceed
                        validateAndProceed();
                    }
                }
                break;
            case RC_SIGN_IN:
            case RC_WELCOME_BACK_IDP:
                finish(resultCode, data);
                break;
        }
    }

    private void validateAndProceed() {
        String email = mEmailEditText.getText().toString();
        if (mEmailFieldValidator.validate(email)) {
            checkAccountExists(email);
        }
    }

    private void checkAccountExists(@NonNull final String email) {
        mHelper.showLoadingDialog(R.string.progress_dialog_checking_accounts);

        // Get name from SmartLock, if possible
        String name = null;
        Uri photoUri = null;
        if (mLastCredential != null && mLastCredential.getId().equals(email)) {
            name = mLastCredential.getName();
            photoUri = mLastCredential.getProfilePictureUri();
        }

        final String finalName = name;
        final Uri finalPhotoUri = photoUri;
        ProviderUtils.fetchTopProvider(mHelper.getFirebaseAuth(), email)
                .addOnSuccessListener(getActivity(), new OnSuccessListener<String>() {
                    @Override
                    public void onSuccess(String provider) {
                        if (provider == null) {
                            mListener.onNewUser(new User.Builder(email)
                                                        .setName(finalName)
                                                        .setPhotoUri(finalPhotoUri)
                                                        .build());
                        } else if (EmailAuthProvider.PROVIDER_ID.equalsIgnoreCase(provider)) {
                            mListener.onExistingEmailUser(new User.Builder(email).build());
                        } else {
                            mListener.onExistingIdpUser(
                                    new User.Builder(email).setProvider(provider).build());
                        }
                    }
                })
                .addOnCompleteListener(
                        getActivity(),
                        new OnCompleteListener<String>() {
                            @Override
                            public void onComplete(@NonNull Task<String> task) {
                                mHelper.dismissDialog();
                            }
                        });
    }

    private void showEmailAutoCompleteHint() {
        try {
            mHelper.startIntentSenderForResult(getEmailHintIntent().getIntentSender(), RC_HINT);
        } catch (IntentSender.SendIntentException e) {
            Log.e(TAG, "Unable to start hint intent", e);
        }
    }

    private void checkEmailValid() {
        if (getView() != null && mEmailFieldValidator != null) {
            if (!mEmailFieldValidator.isValid(mEmailEditText.getText().toString())) {
                getView().findViewById(R.id.button_next).setBackgroundColor(ContextCompat.getColor(getContext(), R.color.sign_up_disabled));
            } else {
                getView().findViewById(R.id.button_next).setBackgroundColor(ContextCompat.getColor(getContext(), R.color.authui_colorAccent));
            }
        }
    }

    private PendingIntent getEmailHintIntent() {
        GoogleApiClient client = new GoogleApiClient.Builder(getContext())
                .addApi(Auth.CREDENTIALS_API)
                .enableAutoManage(getActivity(), GoogleApiHelper.getSafeAutoManageId(),
                                  new GoogleApiClient.OnConnectionFailedListener() {
                                      @Override
                                      public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                                          Log.e(TAG,
                                                "Client connection failed: " + connectionResult.getErrorMessage());
                                      }
                                  })
                .build();

        HintRequest hintRequest = new HintRequest.Builder()
                .setHintPickerConfig(new CredentialPickerConfig.Builder()
                                             .setShowCancelButton(true)
                                             .build())
                .setEmailAddressIdentifierSupported(true)
                .build();

        return Auth.CredentialsApi.getHintPickerIntent(client, hintRequest);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();

        if (id == R.id.button_next) {
            validateAndProceed();
        } else if (id == R.id.email_layout || id == R.id.email) {
            mEmailLayout.setError(null);
        }
    }

    TextWatcher textListener = new TextWatcher() {
        @Override
        public void afterTextChanged(Editable s) {
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start,
                                      int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start,
                                  int before, int count) {
            checkEmailValid(); // Change NEXT button color if needed.
        }
    };
    @Override
    public void onDonePressed() {
        validateAndProceed();
    }
}
