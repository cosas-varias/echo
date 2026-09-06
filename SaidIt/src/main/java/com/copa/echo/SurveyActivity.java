package com.copa.echo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.copa.echo.android.Fonts;
import com.copa.echo.android.Views;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks through the questionnaire in {@code assets/survey.json} one question at a time and saves
 * the answers as a trace of their own, beside the recordings.
 *
 * The screen knows nothing about the questions themselves: it draws whichever of the four node
 * kinds {@link Survey} defines is current and follows the branch the answer points at. Answers
 * are kept until the last question is done, so leaving half way writes nothing.
 */
public class SurveyActivity extends Activity {

    static final String TAG = SurveyActivity.class.getSimpleName();

    private Survey survey;
    /** Nodes already answered, so "back" can walk out the way it came in. */
    private final List<String> visited = new ArrayList<String>();
    private final List<Survey.Answer> answers = new ArrayList<Survey.Answer>();

    private TextView title;
    private TextView step;
    private TextView question;
    private TextView hint;
    private LinearLayout answersLayout;
    private EditText text;
    private Button continueButton;
    private Button backButton;

    SaidItService service;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((SaidItService.BackgroundRecorderBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, SaidItService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService(connection);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_survey, null);
        Views.search(root, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if (view instanceof Button) {
                    ((Button) view).setTypeface(Fonts.bold(SurveyActivity.this));
                } else if (view instanceof TextView) {
                    ((TextView) view).setTypeface("bold".equals(view.getTag())
                            ? Fonts.bold(SurveyActivity.this) : Fonts.regular(SurveyActivity.this));
                }
            }
        });

        final LinearLayout surveyLayout = (LinearLayout) root.findViewById(R.id.survey_layout);
        final FrameLayout frame = new FrameLayout(this) {
            @Override
            protected boolean fitSystemWindows(Rect insets) {
                surveyLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return true;
            }
        };
        frame.addView(root);

        title = (TextView) root.findViewById(R.id.survey_title);
        step = (TextView) root.findViewById(R.id.survey_step);
        question = (TextView) root.findViewById(R.id.survey_question);
        hint = (TextView) root.findViewById(R.id.survey_hint);
        answersLayout = (LinearLayout) root.findViewById(R.id.survey_answers);
        text = (EditText) root.findViewById(R.id.survey_text);
        continueButton = (Button) root.findViewById(R.id.survey_continue);
        backButton = (Button) root.findViewById(R.id.survey_back);

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goBack();
            }
        });

        root.findViewById(R.id.survey_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        setContentView(frame);

        survey = Survey.load(getAssets());
        if (survey == null) {
            Toast.makeText(this, R.string.survey_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        title.setText(survey.title);
        show(survey.node(survey.start));
    }

    private void show(Survey.Node node) {
        if (node == null) {
            save();
            return;
        }

        question.setText(node.question);
        step.setText(getString(R.string.survey_step, answers.size() + 1));
        backButton.setEnabled(!visited.isEmpty());
        backButton.setAlpha(visited.isEmpty() ? 0.4f : 1f);

        hint.setVisibility(node.hint.isEmpty() ? View.GONE : View.VISIBLE);
        hint.setText(node.hint);

        answersLayout.removeAllViews();
        text.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);

        if (Survey.TYPE_SCALE.equals(node.type)) {
            showScale(node);
        } else if (Survey.TYPE_TEXT.equals(node.type)) {
            showText(node);
        } else if (Survey.TYPE_END.equals(node.type)) {
            showEnd();
        } else {
            showChoice(node);
        }
    }

    private void showChoice(final Survey.Node node) {
        for (final Survey.Option option : node.options) {
            final Button button = optionButton(option.label);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    answer(node, option.label, option.next);
                }
            });
            answersLayout.addView(button);
        }
    }

    /** The whole scale on one row, so how far along an answer sits is visible at a glance. */
    private void showScale(final Survey.Node node) {
        final int gap = (int) (4 * getResources().getDisplayMetrics().density);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int value = node.min; value <= node.max; ++value) {
            final int chosen = value;
            final Button button = new Button(this);
            button.setText(Integer.toString(value));
            button.setTypeface(Fonts.bold(this));
            button.setTextColor(getResources().getColor(R.color.gray_f));
            button.setBackgroundResource(R.drawable.gray_button);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(gap, 0, gap, 0);
            button.setLayoutParams(params);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    answer(node, Integer.toString(chosen), node.next);
                }
            });
            row.addView(button);
        }
        answersLayout.addView(row);

        if (node.minLabel.isEmpty() && node.maxLabel.isEmpty()) return;
        final LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.addView(scaleLabel(node.minLabel, android.view.Gravity.START));
        labels.addView(scaleLabel(node.maxLabel, android.view.Gravity.END));
        answersLayout.addView(labels);
    }

    private TextView scaleLabel(String label, int gravity) {
        final TextView view = new TextView(this);
        view.setText(label);
        view.setGravity(gravity);
        view.setTypeface(Fonts.regular(this));
        view.setTextSize(getResources().getDimension(R.dimen.small_text_size)
                / getResources().getDisplayMetrics().scaledDensity);
        view.setTextColor(getResources().getColor(R.color.gray_c));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return view;
    }

    private void showText(final Survey.Node node) {
        text.setVisibility(View.VISIBLE);
        text.setText("");
        continueButton.setVisibility(View.VISIBLE);
        continueButton.setText(node.next == null ? R.string.survey_save : R.string.survey_continue);
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer(node, text.getText().toString().trim(), node.next);
            }
        });
    }

    private void showEnd() {
        continueButton.setVisibility(View.VISIBLE);
        continueButton.setText(R.string.survey_save);
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    private Button optionButton(String label) {
        final Button button = new Button(this);
        button.setText(label);
        button.setTypeface(Fonts.bold(this));
        button.setTextColor(getResources().getColor(R.color.gray_f));
        button.setBackgroundResource(R.drawable.gray_button);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));
        button.setLayoutParams(params);
        return button;
    }

    private void answer(Survey.Node node, String value, String next) {
        // An empty free-text box is not an answer, and writing it down as one would leave the
        // question looking answered with nothing in it.
        if (!value.isEmpty()) {
            answers.add(new Survey.Answer(node.id, node.question, value));
        }
        visited.add(node.id);
        show(survey.node(next));
    }

    private void goBack() {
        if (visited.isEmpty()) return;
        final String previous = visited.remove(visited.size() - 1);
        // The answer to the question being returned to goes with it, so answering again replaces
        // it instead of recording both.
        for (int i = answers.size() - 1; i >= 0; --i) {
            if (previous.equals(answers.get(i).id)) {
                answers.remove(i);
                break;
            }
        }
        show(survey.node(previous));
    }

    private void save() {
        if (answers.isEmpty()) {
            finish();
            return;
        }
        if (service == null) {
            Toast.makeText(this, R.string.survey_cant_save, Toast.LENGTH_LONG).show();
            return;
        }
        service.saveSurvey(survey.toJson(answers, System.currentTimeMillis()),
                new SaidItService.TextTraceReceiver() {
                    @Override
                    public void traceWritten(File file) {
                        if (isFinishing()) return;
                        Toast.makeText(SurveyActivity.this,
                                file == null ? getString(R.string.survey_cant_save)
                                        : getString(R.string.survey_saved, file.getName()),
                                Toast.LENGTH_LONG).show();
                        if (file != null) finish();
                    }
                });
    }
}
