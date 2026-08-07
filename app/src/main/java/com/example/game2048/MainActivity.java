package com.example.game2048;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private GameView gameView;
    private TextView scoreView;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("game2048", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);

        FrameLayout top = new FrameLayout(this);
        top.setPadding(dp(20), dp(30), dp(20), dp(10));

        scoreView = new TextView(this);
        scoreView.setText("分数: 0");
        scoreView.setTextSize(22);
        scoreView.setTextColor(Color.BLACK);
        scoreView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        top.addView(scoreView, slp);

        TextView title = new TextView(this);
        title.setText("2  0  4  8");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#776e65"));
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        tlp.bottomMargin = dp(8);
        title.setLayoutParams(tlp);
        top.addView(title);

        FrameLayout.LayoutParams toplp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toplp.gravity = Gravity.TOP;
        root.addView(top, toplp);

        gameView = new GameView(this);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(gameView, glp);

        setContentView(root);

        gameView.setScoreListener(new GameView.ScoreListener() {
            @Override
            public void onScore(int score) {
                scoreView.setText("分数: " + score);
            }
        });

        gameView.setGameOverListener(new GameView.GameOverListener() {
            @Override
            public void onGameOver(int score) {
                int best = Math.max(prefs.getInt("best", 0), score);
                prefs.edit().putInt("best", best).apply();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("游戏结束")
                        .setMessage("你的分数: " + score + "\n最高分: " + best)
                        .setPositiveButton("再来一局", (d, w) -> gameView.newGame())
                        .setCancelable(false)
                        .show();
            }
        });
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    static class GameView extends View {
        private int[][] board = new int[4][4];
        private int score = 0;
        private boolean gameOver = false;
        private Paint paint = new Paint();

        private ScoreListener scoreListener;
        private GameOverListener gameOverListener;

        interface ScoreListener {
            void onScore(int score);
        }

        interface GameOverListener {
            void onGameOver(int score);
        }

        public GameView(Context context) {
            super(context);
            newGame();
        }

        public void setScoreListener(ScoreListener l) {
            this.scoreListener = l;
        }

        public void setGameOverListener(GameOverListener l) {
            this.gameOverListener = l;
        }

        public void newGame() {
            board = new int[4][4];
            score = 0;
            gameOver = false;
            addRandomTile();
            addRandomTile();
            invalidate();
            if (scoreListener != null) scoreListener.onScore(0);
        }

        private void addRandomTile() {
            java.util.List<int[]> empty = new java.util.ArrayList<>();
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    if (board[r][c] == 0) empty.add(new int[]{r, c});
                }
            }
            if (empty.isEmpty()) return;
            int[] cell = empty.get((int) (Math.random() * empty.size()));
            board[cell[0]][cell[1]] = (Math.random() < 0.9) ? 2 : 4;
        }

        private void moveLine(int[] line) {
            int[] tmp = new int[4];
            int idx = 0;
            for (int i = 0; i < 4; i++) {
                if (line[i] != 0) tmp[idx++] = line[i];
            }
            for (int i = 0; i < 4; i++) line[i] = 0;
            idx = 0;
            for (int i = 0; i < 4; i++) {
                if (tmp[i] == 0) continue;
                if (i + 1 < 4 && tmp[i] == tmp[i + 1]) {
                    line[idx++] = tmp[i] * 2;
                    score += tmp[i] * 2;
                    i++;
                } else {
                    line[idx++] = tmp[i];
                }
            }
        }

        private void move(int dir) {
            int[][] before = new int[4][4];
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    before[r][c] = board[r][c];

            int[] line = new int[4];
            if (dir == 0) { // left
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) line[c] = board[r][c];
                    moveLine(line);
                    for (int c = 0; c < 4; c++) board[r][c] = line[c];
                }
            } else if (dir == 1) { // right
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) line[c] = board[r][3 - c];
                    moveLine(line);
                    for (int c = 0; c < 4; c++) board[r][3 - c] = line[c];
                }
            } else if (dir == 2) { // up
                for (int c = 0; c < 4; c++) {
                    for (int r = 0; r < 4; r++) line[r] = board[r][c];
                    moveLine(line);
                    for (int r = 0; r < 4; r++) board[r][c] = line[r];
                }
            } else { // down
                for (int c = 0; c < 4; c++) {
                    for (int r = 0; r < 4; r++) line[r] = board[3 - r][c];
                    moveLine(line);
                    for (int r = 0; r < 4; r++) board[3 - r][c] = line[r];
                }
            }

            boolean changed = false;
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    if (board[r][c] != before[r][c]) changed = true;

            if (changed) {
                addRandomTile();
                if (scoreListener != null) scoreListener.onScore(score);
            }

            if (noMovesLeft()) {
                gameOver = true;
                if (gameOverListener != null) gameOverListener.onGameOver(score);
            }
            invalidate();
        }

        private boolean noMovesLeft() {
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    if (board[r][c] == 0) return false;
                    if (c + 1 < 4 && board[r][c] == board[r][c + 1]) return false;
                    if (r + 1 < 4 && board[r][c] == board[r + 1][c]) return false;
                }
            }
            return true;
        }

        private float startX, startY;

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getX();
                    startY = e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = e.getX() - startX;
                    float dy = e.getY() - startY;
                    if (Math.abs(dx) < dp(20) && Math.abs(dy) < dp(20)) return true;
                    if (Math.abs(dx) > Math.abs(dy)) {
                        move(dx > 0 ? 1 : 0);
                    } else {
                        move(dy > 0 ? 3 : 2);
                    }
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private int dp(int v) {
            return Math.round(getResources().getDisplayMetrics().density * v);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = getWidth();
            float gap = size * 0.03f;
            float cell = (size - gap * 5) / 4f;

            // background
            paint.setColor(0xffbbada0);
            canvas.drawRoundRect(new RectF(0, 0, size, size), dp(10), dp(10), paint);

            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    float x = gap + c * (cell + gap);
                    float y = gap + r * (cell + gap);
                    int v = board[r][c];
                    paint.setColor(tileColor(v));
                    canvas.drawRoundRect(new RectF(x, y, x + cell, y + cell), dp(6), dp(6), paint);
                    if (v != 0) {
                        paint.setColor(v > 4 ? Color.WHITE : 0xff776e65);
                        paint.setTextSize(cell * 0.4f);
                        paint.setTextAlign(Paint.Align.CENTER);
                        Paint.FontMetrics fm = paint.getFontMetrics();
                        float baseline = y + cell / 2 - (fm.ascent + fm.descent) / 2;
                        canvas.drawText(String.valueOf(v), x + cell / 2, baseline, paint);
                    }
                }
            }
        }

        private int tileColor(int v) {
            switch (v) {
                case 0: return 0xffcdc1b4;
                case 2: return 0xffeee4da;
                case 4: return 0xffede0c8;
                case 8: return 0xfff2b179;
                case 16: return 0xfff59563;
                case 32: return 0xfff67c5f;
                case 64: return 0xfff65e3b;
                case 128: return 0xffedcf72;
                case 256: return 0xffedcc61;
                case 512: return 0xffedc850;
                case 1024: return 0xffedc53f;
                case 2048: return 0xffedc22e;
                default: return 0xff3c3a32;
            }
        }
    }
}
