package com.firebase.ui.auth.ui.email;


import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.design.widget.TextInputLayout;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.firebase.ui.auth.IdpResponse;
import com.firebase.ui.auth.R;
import com.firebase.ui.auth.provider.ProviderUtils;
import com.firebase.ui.auth.ui.ExtraConstants;
import com.firebase.ui.auth.ui.FlowParameters;
import com.firebase.ui.auth.ui.FragmentBase;
import com.firebase.ui.auth.ui.ImeHelper;
import com.firebase.ui.auth.ui.TaskFailureLogger;
import com.firebase.ui.auth.ui.TermsTextView;
import com.firebase.ui.auth.ui.User;
import com.firebase.ui.auth.ui.accountlink.WelcomeBackIdpPrompt;
import com.firebase.ui.auth.ui.accountlink.WelcomeBackPasswordPrompt;
import com.firebase.ui.auth.ui.email.fieldvalidators.EmailFieldValidator;
import com.firebase.ui.auth.ui.email.fieldvalidators.PasswordFieldValidator;
import com.firebase.ui.auth.ui.email.fieldvalidators.RequiredFieldValidator;
import com.firebase.ui.auth.util.signincontainer.SaveSmartLock;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

/**
 * Fragment to display an email/name/password sign up form for new users.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class RegisterEmailFragment extends FragmentBase implements
        View.OnClickListener, View.OnFocusChangeListener, ImeHelper.DonePressedListener {

    public static final String TAG = "RegisterEmailFragment";

    private EditText mEmailEditText;
    private EditText mNameEditText;
    private EditText mPasswordEditText;
    private TermsTextView mAgreementText;
    private TextInputLayout mEmailInput;
    private TextInputLayout mPasswordInput;

    private EmailFieldValidator mEmailFieldValidator;
    private PasswordFieldValidator mPasswordFieldValidator;
    private RequiredFieldValidator mNameValidator;
    private SaveSmartLock mSaveSmartLock;

    private User mUser;

    public static RegisterEmailFragment newInstance(FlowParameters flowParameters, User user) {
        RegisterEmailFragment fragment = new RegisterEmailFragment();

        Bundle args = new Bundle();
        args.putParcelable(ExtraConstants.EXTRA_FLOW_PARAMS, flowParameters);
        args.putParcelable(ExtraConstants.EXTRA_USER, user);

        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            mUser = User.getUser(getArguments());
        } else {
            mUser = User.getUser(savedInstanceState);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.register_email_layout, container, false);

        mEmailEditText = (EditText) v.findViewById(R.id.email);
        mNameEditText = (EditText) v.findViewById(R.id.name);
        mPasswordEditText = (EditText) v.findViewById(R.id.password);
        mAgreementText = (TermsTextView) v.findViewById(R.id.create_account_text);
        mEmailInput = (TextInputLayout) v.findViewById(R.id.email_layout);
        mPasswordInput = (TextInputLayout) v.findViewById(R.id.password_layout);

        mPasswordFieldValidator = new PasswordFieldValidator(
                mPasswordInput,
                getResources().getInteger(R.integer.min_password_length));
        mNameValidator = new RequiredFieldValidator(
                (TextInputLayout) v.findViewById(R.id.name_layout));
        mEmailFieldValidator = new EmailFieldValidator(mEmailInput);

        ImeHelper.setImeOnDoneListener(mPasswordEditText, this);

        mEmailEditText.setOnFocusChangeListener(this);
        mNameEditText.setOnFocusChangeListener(this);
        mPasswordEditText.setOnFocusChangeListener(this);
        // Handles finish button color change
        mNameEditText.addTextChangedListener(textListener);
        mEmailEditText.addTextChangedListener(textListener);
        mPasswordEditText.addTextChangedListener(textListener);

        TextView buttonCreate = (TextView) v.findViewById(R.id.button_create);
        buttonCreate.setOnClickListener(this);
        buttonCreate.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.sign_up_disabled));

        if (savedInstanceState != null) {
            return v;
        }

        // If we press enter on soft-keyboard it simulates finish button click
        mPasswordEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE && getView() != null) {
                    onClick(getView().findViewById(R.id.button_create));
                    return true;
                } else {
                    return false;
                }
            }
        });

        // If email is passed in, fill in the field and move down to the name field.
        String email = mUser.getEmail();
        if (!TextUtils.isEmpty(email)) {
            mEmailEditText.setText(email);
        }

        // If name is passed in, fill in the field and move down to the password field.
        String name = mUser.getName();
        if (!TextUtils.isEmpty(name)) {
            mNameEditText.setText(name);
        }

        // See http://stackoverflow.com/questions/11082341/android-requestfocus-ineffective#comment51774752_11082523
        if (!TextUtils.isEmpty(mNameEditText.getText())) {
            safeRequestFocus(mPasswordEditText);
        } else if (!TextUtils.isEmpty(mEmailEditText.getText())) {
            safeRequestFocus(mNameEditText);
        } else {
            safeRequestFocus(mEmailEditText);
        }

        return v;

    }

    private void safeRequestFocus(final View v) {
        v.post(new Runnable() {
            @Override
            public void run() {
                v.requestFocus();
                // Sometimes it doesn't receive focus.
                if (getActivity().getCurrentFocus() != null) {
                    InputMethodManager inputMethodManager = (InputMethodManager) getActivity().getSystemService(
                            Context.INPUT_METHOD_SERVICE);
                    inputMethodManager.showSoftInput(getActivity().getCurrentFocus(), 0);
                }
            }
        });
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getActivity().setTitle(R.string.title_register_email);

        mSaveSmartLock = mHelper.getSaveSmartLockInstance(getActivity());
        mAgreementText.showTerms(mHelper.getFlowParams(), R.string.button_text_save);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelable(ExtraConstants.EXTRA_USER,
                               new User.Builder(mEmailEditText.getText().toString())
                                       .setName(mNameEditText.getText().toString())
                                       .setPhotoUri(mUser.getPhotoUri())
                                       .build());
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        if (hasFocus) return; // Only consider fields losing focus

        int id = view.getId();
        if (id == R.id.email) {
            mEmailFieldValidator.validate(mEmailEditText.getText());
        } else if (id == R.id.name) {
            mNameValidator.validate(mNameEditText.getText());
        } else if (id == R.id.password) {
            mPasswordFieldValidator.validate(mPasswordEditText.getText());
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.button_create) {
            validateAndRegisterUser();
        }
    }

    @Override
    public void onDonePressed() {
        validateAndRegisterUser();
    }

    private void validateAndRegisterUser() {
        String email = mEmailEditText.getText().toString();
        String password = mPasswordEditText.getText().toString();
        String name = mNameEditText.getText().toString();

        boolean emailValid = mEmailFieldValidator.validate(email);
        boolean passwordValid = mPasswordFieldValidator.validate(password);
        boolean nameValid = mNameValidator.validate(name);
        if (emailValid && passwordValid && nameValid) {
            mHelper.showLoadingDialog(R.string.progress_dialog_signing_up);
            registerUser(email, name, password);
        }
    }

    private void checkAllFieldsValid() {
        if (getView() != null) {
            TextView buttonSignUp = (TextView) getView().findViewById(R.id.button_create);
            if (mEmailFieldValidator.isValid(mEmailEditText.getText().toString()) && mPasswordFieldValidator.isValid(mPasswordEditText.getText().toString()) && mNameValidator.isValid(mNameEditText.getText().toString())) {
                buttonSignUp.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.authui_colorAccent));
            } else if (buttonSignUp != null) {
                buttonSignUp.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.sign_up_disabled));
            }
        }
    }

    private void registerUser(final String email, final String name, final String password) {
        mHelper.getFirebaseAuth()
                .createUserWithEmailAndPassword(email, password)
                .addOnFailureListener(new TaskFailureLogger(TAG, "Error creating user"))
                .addOnSuccessListener(new OnSuccessListener<AuthResult>() {
                    @Override
                    public void onSuccess(AuthResult authResult) {
                        // Set display name
                        UserProfileChangeRequest changeNameRequest =
                                new UserProfileChangeRequest.Builder()
                                        .setDisplayName(name)
                                        .setPhotoUri(mUser.getPhotoUri())
                                        .build();

                        final FirebaseUser user = authResult.getUser();
                        user.updateProfile(changeNameRequest)
                                .addOnFailureListener(new TaskFailureLogger(
                                        TAG, "Error setting display name"))
                                .addOnCompleteListener(new OnCompleteListener<Void>() {
                                    @Override
                                    public void onComplete(@NonNull Task<Void> task) {
                                        // This executes even if the name change fails, since
                                        // the account creation succeeded and we want to save
                                        // the credential to SmartLock (if enabled).
                                        mHelper.saveCredentialsOrFinish(
                                                mSaveSmartLock,
                                                getActivity(),
                                                user,
                                                password,
                                                new IdpResponse.Builder(EmailAuthProvider.PROVIDER_ID,
                                                                        email)
                                                        .build());
                                    }
                                });
                    }
                })
                .addOnFailureListener(getActivity(), new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        if (e instanceof FirebaseAuthWeakPasswordException) {
                            // Password too weak
                            mPasswordInput.setError(getResources().getQuantityString(
                                    R.plurals.error_weak_password, R.integer.min_password_length));
                        } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
                            // Email address is malformed
                            mEmailInput.setError(getString(R.string.invalid_email_address));
                        } else if (e instanceof FirebaseAuthUserCollisionException) {
                            // Collision with existing user email, it should be very hard for
                            // the user to even get to this error due to CheckEmailFragment.

                            ProviderUtils.fetchTopProvider(mHelper.getFirebaseAuth(), email).addOnSuccessListener(
                                    getActivity(),
                                    new OnSuccessListener<String>() {
                                        @Override
                                        public void onSuccess(String provider) {
                                            Toast.makeText(getContext(),
                                                           R.string.error_user_collision,
                                                           Toast.LENGTH_LONG)
                                                    .show();

                                            if (provider == null) {
                                                String msg =
                                                        "User has no providers even though " +
                                                        "we got a " +
                                                        "FirebaseAuthUserCollisionException";
                                                throw new IllegalStateException(msg);
                                            } else if (EmailAuthProvider.PROVIDER_ID.equalsIgnoreCase(
                                                    provider)) {
                                                getActivity().startActivityForResult(
                                                        WelcomeBackPasswordPrompt.createIntent(
                                                                getContext(),
                                                                mHelper.getFlowParams(),
                                                                new IdpResponse.Builder(
                                                                        EmailAuthProvider.PROVIDER_ID,
                                                                        email).build()),
                                                        RegisterEmailActivity.RC_WELCOME_BACK_IDP);
                                            } else {
                                                getActivity().startActivityForResult(
                                                        WelcomeBackIdpPrompt.createIntent(
                                                                getContext(),
                                                                mHelper.getFlowParams(),
                                                                new User.Builder(email)
                                                                        .setProvider(provider)
                                                                        .build(),
                                                                new IdpResponse.Builder(
                                                                        EmailAuthProvider.PROVIDER_ID,
                                                                        email).build()),
                                                        RegisterEmailActivity.RC_WELCOME_BACK_IDP);
                                            }
                                        }
                                    })
                                    .addOnCompleteListener(new OnCompleteListener<String>() {
                                        @Override
                                        public void onComplete(@NonNull Task<String> task) {
                                            mHelper.dismissDialog();
                                        }
                                    });
                            return;
                        } else {
                            // General error message, this branch should not be invoked but
                            // covers future API changes
                            mEmailInput.setError(getString(R.string.email_account_creation_error));
                        }

                        mHelper.dismissDialog();
                    }
                });
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
            checkAllFieldsValid(); // Change SIGN UP button color if needed.
        }
    };
}
