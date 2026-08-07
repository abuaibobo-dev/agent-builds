package com.example.game2048;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameView = new GameView(this);
        setContentView(gameView);
        gameView.newGame();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (gameView != null) gameView.saveState(outState);
    }
}

class GameView extends View {
    private static final int SIZE = 4;
    private int[][] grid;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rand = new Random();
    private int score = 0;
    private boolean won = false;

    public GameView(Context c) { super(c); init(); }
    public GameView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        grid = new int[SIZE][SIZE];
        setFocusable(true);
        final GestureDetector gd = new GestureDetector(getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) { return true; }
                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > 0) move(0, 1); else move(0, -1);
                    } else {
                        if (dy > 0) move(1, 0); else move(1, -1);
                    }
                    return true;
                }
            });
        setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
        });
    }

    public void newGame() {
        for (int i = 0; i < SIZE; i++) for (int j = 0; j < SIZE; j++) grid[i][j] = 0;
        score = 0; won = false;
        spawn(); spawn();
        invalidate();
    }

    private void spawn() {
        List<int[]> empty = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) for (int j = 0; j < SIZE; j++)
            if (grid[i][j] == 0) empty.add(new int[]{i, j});
        if (empty.isEmpty()) return;
        int[] p = empty.get(rand.nextInt(empty.size()));
        grid[p[0]][p[1]] = (rand.nextInt(10) == 0) ? 4 : 2;
    }

    private void move(int dy, int dx) {
        int[][] old = new int[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++) System.arraycopy(grid[i], 0, old[i], 0, SIZE);
        boolean changed = false;

        for (int axis = 0; axis < SIZE; axis++) {
            int[] out = new int[SIZE];
            int p = 0;
            // read line in movement order
            for (int s = 0; s < SIZE; s++) {
                int r = (dy == 0) ? axis : (dy == 1 ? (SIZE - 1 - s) : s);
                int c = (dx == 0) ? axis : (dx == 1 ? (SIZE - 1 - s) : s);
                int val = old[r][c];
                if (val == 0) continue;
                if (p > 0 && out[p - 1] == val) {
                    out[p - 1] = val * 2;
                    score += val * 2;
                    if (out[p - 1] == 2048) won = true;
                    changed = true;
                } else {
                    out[p++] = val;
                }
            }
            // write back + detect change
            for (int s = 0; s < SIZE; s++) {
                int r = (dy == 0) ? axis : (dy == 1 ? (SIZE - 1 - s) : s);
                int c = (dx == 0) ? axis : (dx == 1 ? (SIZE - 1 - s) : s);
                if (grid[r][c] != out[s]) changed = true;
                grid[r][c] = out[s];
            }
        }

        if (changed) {
            spawn();
            invalidate();
        }
        // game over check
        if (changed && !hasMove()) {
            Toast.makeText(getContext(), "Game Over! Score: " + score, Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasMove() {
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                if (grid[i][j] == 0) return true;
                if (i + 1 < SIZE && grid[i][j] == grid[i + 1][j]) return true;
                if (j + 1 < SIZE && grid[i][j] == grid[i][j + 1]) return true;
            }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int topPad = (int) (h * 0.18f);
        int area = Math.min(w, h - topPad);
        int left = (w - area) / 2;
        float gap = area * 0.018f;
        float cell = (area - gap * (SIZE + 1)) / SIZE;

        canvas.drawColor(Color.rgb(250,247,240));

        textPaint.setColor(Color.rgb(119,110,101));
        textPaint.setTextSize(area * 0.055f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("2048  SCORE: " + score, left, topPad * 0.45f, textPaint);
        if (won) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setColor(Color.rgb(0,150,136));
            canvas.drawText("YOU WIN!", left + area, topPad * 0.45f, textPaint);
        }

        bgPaint.setColor(Color.rgb(187,173,160));
        canvas.drawRoundRect(new RectF(left, topPad, left + area, topPad + area), gap * 4, gap * 4, bgPaint);

        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                float x = left + gap + j * (cell + gap);
                float y = topPad + gap + i * (cell + gap);
                int val = grid[i][j];
                cellPaint.setColor(tileColor(val));
                canvas.drawRoundRect(new RectF(x, y, x + cell, y + cell), gap * 3, gap * 3, cellPaint);
                if (val != 0) {
                    textPaint.setColor(textColor(val));
                    textPaint.setTextSize(cell * sizeFor(val));
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    Paint.FontMetrics fm = textPaint.getFontMetrics();
                    float ty = y + cell / 2f - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText(String.valueOf(val), x + cell / 2f, ty, textPaint);
                }
            }
        }
    }

    private float sizeFor(int v) {
        if (v < 100) return 0.42f;
        if (v < 1000) return 0.34f;
        return 0.26f;
    }

    private int tileColor(int v) {
        switch (v) {
            case 0: return Color.rgb(205,193,180);
            case 2: return Color.rgb(238,228,218);
            case 4: return Color.rgb(237,224,200);
            case 8: return Color.rgb(242,177,121);
            case 16: return Color.rgb(245,149,99);
            case 32: return Color.rgb(246,124,95);
            case 64: return Color.rgb(246,94,59);
            case 128: return Color.rgb(237,207,114);
            case 256: return Color.rgb(237,204,97);
            case 512: return Color.rgb(237,200,80);
            default: return Color.rgb(237,197,80);
        }
    }

    private int textColor(int v) {
        return (v <= 4) ? Color.rgb(119,110,101) : Color.WHITE;
    }

    public void saveState(Bundle out) {
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++)
                out.putInt("g" + i + "_" + j, grid[i][j]);
        out.putInt("score", score);
    }
}
