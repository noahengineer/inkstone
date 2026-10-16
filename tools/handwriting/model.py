"""The synthesis network -- the reference implementation (docs/HANDWRITING-SYNTHESIS.md §7.1).

Graves 2013 §5, sized down, plus a per-writer style vector:

    layer 1    LSTM( [x_t, w_{t-1}, s] )                       x_t = (dx, dy, pen-lift), normalised
    window     (alpha, beta, kappa) from layer 1; phi(u) over the text; w_t = sum_u phi(u) c_u
    layer l>1  LSTM( [x_t, h^{l-1}_t, w_t, s] )
    output     Linear( [h^1 .. h^L] ) -> pen-lift logit, then M bivariate Gaussians:
               [e | pi(M) | mu_x(M) | mu_y(M) | sigma_x(M) | sigma_y(M) | rho(M)]

alpha = exp, beta = exp, kappa_t = kappa_{t-1} + exp(k) / kappa_scale -- the scale is the corpus's
points per character, so an untrained window moves about a character per character's worth of
points. Gate order is PyTorch's (i, f, g, o). Every one of these choices is mirrored, number for
number, by Kotlin's SynthesisNetwork, and export.py's golden vectors are what prove it (§7.3).

Neatness is Graves's sampling bias b (eqs. 61-62): sigma -> exp(sigma_hat - b), pi ∝ exp(pi_hat (1 + b)).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import torch
from torch import nn


@dataclass
class Arch:
    alphabet: str
    layers: int = 3
    cells: int = 256
    window: int = 10
    mixtures: int = 20
    style_dim: int = 32
    writers: int = 1
    kappa_scale: float = 14.0

    @property
    def A(self) -> int:  # noqa: N802 -- the paper's symbol
        return len(self.alphabet)

    @property
    def outputs(self) -> int:
        return 1 + 6 * self.mixtures


@dataclass
class State:
    """Everything a step carries to the next, for a batch of B sequences."""

    h: list  # L tensors (B, H)
    c: list  # L tensors (B, H)
    kappa: torch.Tensor  # (B, K)
    w: torch.Tensor  # (B, A): the last window


def lstm_step(x, h, c, w_ih, w_hh, b):
    """One PyTorch-order LSTM step: gates = W_ih x + W_hh h + b, split (i, f, g, o)."""
    z = x @ w_ih.T + h @ w_hh.T + b
    i, f, g, o = z.chunk(4, dim=1)
    c = torch.sigmoid(f) * c + torch.sigmoid(i) * torch.tanh(g)
    return torch.sigmoid(o) * torch.tanh(c), c


class Synthesis(nn.Module):

    def __init__(self, arch: Arch):
        super().__init__()
        self.arch = arch
        A, H, S, K = arch.A, arch.cells, arch.style_dim, arch.window
        self.style = nn.Embedding(arch.writers, S)
        nn.init.normal_(self.style.weight, std=0.1)
        self.lstm1 = nn.LSTMCell(3 + A + S, H)
        self.window_layer = nn.Linear(H, 3 * K)
        self.upper = nn.ModuleList([nn.LSTM(3 + H + A + S, H, batch_first=True) for _ in range(arch.layers - 1)])
        self.output = nn.Linear(arch.layers * H, arch.outputs)

    # --- weights as plain tensors, the way export.py and the stepper see them -----------------------

    def layer_weights(self, layer: int):
        """(w_ih, w_hh, b) for `layer`, with the two PyTorch biases summed."""
        if layer == 0:
            m = self.lstm1
            return m.weight_ih, m.weight_hh, m.bias_ih + m.bias_hh
        m = self.upper[layer - 1]
        return m.weight_ih_l0, m.weight_hh_l0, m.bias_ih_l0 + m.bias_hh_l0

    # --- the window -------------------------------------------------------------------------------------

    def window(self, h1, kappa_prev, onehot, text_len):
        """phi over positions 0..U (U = one past the last character) and the window vector.

        onehot (B, U, A); text_len (B,). Returns w (B, A), kappa (B, K), phi (B, U + 1).
        """
        K = self.arch.window
        p = self.window_layer(h1)
        alpha = torch.exp(p[:, :K])
        beta = torch.exp(p[:, K:2 * K])
        kappa = kappa_prev + torch.exp(p[:, 2 * K:]) / self.arch.kappa_scale
        U = onehot.shape[1]
        u = torch.arange(U + 1, dtype=h1.dtype, device=h1.device)
        phi = (alpha.unsqueeze(2) * torch.exp(-beta.unsqueeze(2) * (kappa.unsqueeze(2) - u) ** 2)).sum(dim=1)
        inside = (u.unsqueeze(0) < text_len.unsqueeze(1)).to(h1.dtype)
        w = torch.bmm((phi[:, :U] * inside[:, :U]).unsqueeze(1), onehot).squeeze(1)
        return w, kappa, phi

    # --- teacher-forced, for training and for golden vectors --------------------------------------------

    def forward(self, x, onehot, text_len, style, state: State | None = None, keep_phi: bool = False):
        """x (B, T, 3) inputs; returns (raw outputs (B, T, outputs), final State, phi or None)."""
        B, T, _ = x.shape
        H, L = self.arch.cells, self.arch.layers
        state = state or self.zero_state(B, x.device)
        h1, c1 = state.h[0], state.c[0]
        kappa, w = state.kappa, state.w
        h1s, ws, phis = [], [], []
        for t in range(T):
            h1, c1 = self.lstm1(torch.cat([x[:, t], w, style], dim=1), (h1, c1))
            w, kappa, phi = self.window(h1, kappa, onehot, text_len)
            h1s.append(h1)
            ws.append(w)
            if keep_phi:
                phis.append(phi)
        H1 = torch.stack(h1s, dim=1)
        W = torch.stack(ws, dim=1)
        S = style.unsqueeze(1).expand(B, T, style.shape[1])
        hs, hN, cN = [H1], [h1], [c1]
        below = H1
        for l, lstm in enumerate(self.upper, start=1):
            out, (hn, cn) = lstm(torch.cat([x, below, W, S], dim=2), (state.h[l].unsqueeze(0), state.c[l].unsqueeze(0)))
            hs.append(out)
            hN.append(hn.squeeze(0))
            cN.append(cn.squeeze(0))
            below = out
        y = self.output(torch.cat(hs, dim=2))
        phi_all = torch.stack(phis, dim=1) if keep_phi else None
        return y, State(hN, cN, kappa, w), phi_all

    def zero_state(self, B, device=None) -> State:
        H, L, K, A = self.arch.cells, self.arch.layers, self.arch.window, self.arch.A
        z = lambda *s: torch.zeros(*s, device=device)  # noqa: E731
        return State([z(B, H) for _ in range(L)], [z(B, H) for _ in range(L)], z(B, K), z(B, A))

    # --- one step, for sampling (the code path Kotlin mirrors) -----------------------------------------

    def step(self, x, onehot, text_len, style, state: State):
        """One step for a batch: returns (raw output (B, outputs), new State, phi (B, U + 1))."""
        h, c = [], []
        w_ih, w_hh, b = self.layer_weights(0)
        h1, c1 = lstm_step(torch.cat([x, state.w, style], dim=1), state.h[0], state.c[0], w_ih, w_hh, b)
        w, kappa, phi = self.window(h1, state.kappa, onehot, text_len)
        h.append(h1)
        c.append(c1)
        below = h1
        for l in range(1, self.arch.layers):
            w_ih, w_hh, b = self.layer_weights(l)
            hl, cl = lstm_step(torch.cat([x, below, w, style], dim=1), state.h[l], state.c[l], w_ih, w_hh, b)
            h.append(hl)
            c.append(cl)
            below = hl
        y = self.output(torch.cat(h, dim=1))
        return y, State(h, c, kappa, w), phi


def sequence_loss(model: Synthesis, x, target, onehot, text_len, style, adapters=None):
    """Sum over steps of the per-point NLL for one sequence, through `step`'s path (what Kotlin mirrors).

    The reference for docs/HANDWRITING-SYNTHESIS.md §8.1's P2 and P3: autograd through this is what
    Kotlin's hand-written `TrainableNetwork` backward pass is checked against. `adapters`, if given, is
    (ihA, ihB, hhA, hhB, outA, outB): the top layer's W_ih + ihB @ ihA, W_hh + hhB @ hhA, and the output's
    W + outB @ outA. x and target are (T, 3); style is (1, S).
    """
    arch = model.arch
    state = model.zero_state(1)
    total = x.new_zeros(())
    mask = torch.ones(1, 1)
    top = arch.layers - 1
    for t in range(x.shape[0]):
        h, c = [], []
        xt = x[t:t + 1]
        w_ih, w_hh, b = model.layer_weights(0)
        h1, c1 = lstm_step(torch.cat([xt, state.w, style], dim=1), state.h[0], state.c[0], w_ih, w_hh, b)
        w, kappa, phi = model.window(h1, state.kappa, onehot, text_len)
        h.append(h1)
        c.append(c1)
        below = h1
        for l in range(1, arch.layers):
            w_ih, w_hh, b = model.layer_weights(l)
            if adapters is not None and l == top:
                w_ih = w_ih + adapters[1] @ adapters[0]
                w_hh = w_hh + adapters[3] @ adapters[2]
            hl, cl = lstm_step(torch.cat([xt, below, w, style], dim=1), state.h[l], state.c[l], w_ih, w_hh, b)
            h.append(hl)
            c.append(cl)
            below = hl
        cat = torch.cat(h, dim=1)
        y = model.output(cat)
        if adapters is not None:
            y = y + cat @ (adapters[5] @ adapters[4]).T
        total = total + nll(y.unsqueeze(1), target[t:t + 1].unsqueeze(0), mask, arch.mixtures)
        state = State(h, c, kappa, w)
    return total


# --- The mixture ----------------------------------------------------------------------------------------


def split(y, M):
    """Raw outputs -> (e_logit, pi_hat, mu_x, mu_y, sigma_hat_x, sigma_hat_y, rho_hat)."""
    return (y[..., 0], y[..., 1:1 + M], y[..., 1 + M:1 + 2 * M], y[..., 1 + 2 * M:1 + 3 * M],
            y[..., 1 + 3 * M:1 + 4 * M], y[..., 1 + 4 * M:1 + 5 * M], y[..., 1 + 5 * M:1 + 6 * M])


def nll(y, target, mask, M):
    """Negative log-likelihood per point (Graves eq. 26), averaged over unmasked points."""
    e, pi_hat, mx, my, sx_hat, sy_hat, rho_hat = split(y, M)
    dx = target[..., 0:1]
    dy = target[..., 1:2]
    sx, sy = torch.exp(sx_hat), torch.exp(sy_hat)
    rho = torch.tanh(rho_hat) * 0.9999
    zx = (dx - mx) / sx
    zy = (dy - my) / sy
    one_minus = 1.0 - rho * rho
    log_n = (-(zx * zx + zy * zy - 2.0 * rho * zx * zy) / (2.0 * one_minus)
             - torch.log(2.0 * math.pi * sx * sy * torch.sqrt(one_minus)))
    log_mix = torch.logsumexp(torch.log_softmax(pi_hat, dim=-1) + log_n, dim=-1)
    pen = nn.functional.binary_cross_entropy_with_logits(e, target[..., 2], reduction="none")
    per_point = -log_mix + pen
    return (per_point * mask).sum() / mask.sum().clamp(min=1.0)


def draw(y, M, bias: float, gen: torch.Generator):
    """A sample (B, 3) from the mixture with Graves's bias."""
    e, pi_hat, mx, my, sx_hat, sy_hat, rho_hat = split(y, M)
    pi = torch.softmax(pi_hat * (1.0 + bias), dim=-1)
    k = torch.multinomial(pi, 1, generator=gen).squeeze(1)
    pick = lambda t: t.gather(1, k.unsqueeze(1)).squeeze(1)  # noqa: E731
    sx, sy = torch.exp(pick(sx_hat) - bias), torch.exp(pick(sy_hat) - bias)
    rho = torch.tanh(pick(rho_hat))
    n1 = torch.randn(k.shape, generator=gen)
    n2 = torch.randn(k.shape, generator=gen)
    dx = pick(mx) + sx * n1
    dy = pick(my) + sy * (rho * n1 + torch.sqrt(torch.clamp(1 - rho * rho, min=0.0)) * n2)
    lift = (torch.rand(k.shape, generator=gen) < torch.sigmoid(e)).float()
    return torch.stack([dx, dy, lift], dim=1)


def onehot_texts(texts: list[str], alphabet: str):
    index = {c: i for i, c in enumerate(alphabet)}
    U = max(1, max(len(t) for t in texts))
    out = torch.zeros(len(texts), U, len(alphabet))
    for b, t in enumerate(texts):
        for u, ch in enumerate(t):
            out[b, u, index[ch]] = 1.0
    return out, torch.tensor([len(t) for t in texts], dtype=torch.float32)


@torch.no_grad()
def sample(model: Synthesis, texts: list[str], style, bias: float = 0.5, seed: int = 0,
           max_steps_per_char: int = 40, prime=None, norm=None):
    """Generate one trajectory per text, batched. Returns [(normalised offsets (N, 3), finished)].

    `prime` is (prime_text, prime_x (N + 1, 3)): the start token and the prime's N points as normalised
    inputs. The network reads all but the last; the last is held back as every word's first input, so a
    word continues the line as a phrase's second word does in training -- Kotlin's
    `KotlinSynthesisNetwork.prime` does the same. Every text is written as prime_text + " " + text
    (§7.4). A word stops when the window has moved past its last character (Graves: phi at U exceeds
    every phi before it) and the pen lifts, or at the runaway guard -- MAX steps per character of the
    word -- which counts as unfinished. `norm` is the corpus's ((mean_x, mean_y), (std_x, std_y)), which
    the grace for i-dots and t-bars needs to know where the pen is (WordEnd).
    """
    model.eval()
    gen = torch.Generator().manual_seed(seed)
    B = len(texts)
    state = model.zero_state(B)
    words = list(texts)
    x = torch.zeros(B, 3)
    x[:, 2] = 1.0
    if prime is not None:
        p_text, p_x = prime
        p_hot, p_len = onehot_texts([p_text], model.arch.alphabet)
        _, p_state, _ = model(p_x[:-1].unsqueeze(0), p_hot, p_len, style[:1])
        # Kotlin's catchUp (docs §18.10): on a real hand the window lags the prime's ink, and the word
        # would begin by writing out the rest of the prime. Move it up to the prime's end, never back.
        alpha = torch.exp(model.window_layer(p_state.h[0])[:, :model.arch.window])
        centre = (alpha * p_state.kappa).sum(dim=1) / alpha.sum(dim=1)
        kappa = p_state.kappa + (len(p_text) - centre).clamp(min=0).unsqueeze(1)
        state = State([t.expand(B, -1).clone() for t in p_state.h], [t.expand(B, -1).clone() for t in p_state.c],
                      kappa.expand(B, -1).clone(), p_state.w.expand(B, -1).clone())
        texts = [p_text + " " + t for t in texts]
        x[:] = p_x[-1]
    onehot, lens = onehot_texts(texts, model.arch.alphabet)
    guard = [max_steps_per_char * (len(w) + 1) for w in words]
    grace = int(model.arch.kappa_scale * GRACE_CHARS)
    ends = [WordEnd(norm) for _ in range(B)]
    steps = 0
    for _ in range(max(guard) + grace):
        y, state, phi = model.step(x, onehot, lens, style.expand(B, -1), state)
        x = draw(y, model.arch.mixtures, bias, gen)
        steps += 1
        for b in range(B):
            w = ends[b]
            if w.done:
                continue
            w.take(x[b])
            if not w.in_grace:
                U = int(lens[b])
                if w.lifted and phi[b, U] > phi[b, :U].max():
                    w.start_grace(grace)
                elif steps >= guard[b]:
                    w.done = True
        if all(w.done for w in ends):
            break
    return [w.result() for w in ends]


# After Graves's stop, how long the marks may take, how near the word they start, how long they run --
# Kotlin's KotlinSynthesisNetwork.WordEnd, number for number.
GRACE_CHARS = 2.0
MARK_REACH = 0.3
MARK_LENGTH = 1.2


class WordEnd:
    """A word's points as drawn, and the grace after Graves's stop in which its i-dots and t-bars come.

    Graves stops at the first pen lift after the window passes the last letter. The marks a hand adds
    after the word come after that lift, so without a grace `cation` is written `calion`. A stroke
    that starts over the word's own ink and stays short is a mark and is kept; anything else -- or a
    stroke unfinished when the grace runs out -- ends the word before it.
    """

    def __init__(self, norm):
        self.mean, self.std = norm if norm is not None else ((0.0, 0.0), (1.0, 1.0))
        self.recorded, self.committed = [], 0
        self.px = self.py = 0.0
        self.min_x, self.max_x = float("inf"), float("-inf")
        self.stroke_x, self.stroke_len, self.new_stroke = 0.0, 0.0, True
        self.lifted = self.in_grace = self.done = self.finished = False
        self.grace_left = 0

    def take(self, point):
        dx = float(point[0]) * self.std[0] + self.mean[0]
        dy = float(point[1]) * self.std[1] + self.mean[1]
        lift = float(point[2]) > 0.5
        self.px += dx
        self.py += dy
        self.recorded.append(point.clone())
        if self.new_stroke:
            self.stroke_x, self.stroke_len = self.px, 0.0
        else:
            self.stroke_len += (dx * dx + dy * dy) ** 0.5
        if not self.in_grace:
            self.min_x, self.max_x = min(self.min_x, self.px), max(self.max_x, self.px)
            if lift:
                self.committed = len(self.recorded)
        else:
            self.grace_left -= 1
            off = self.stroke_x > self.max_x + MARK_REACH or self.stroke_x < self.min_x - MARK_REACH
            if off or self.stroke_len > MARK_LENGTH:
                self.done = True
            elif lift:
                self.committed = len(self.recorded)
            elif self.grace_left <= 0:
                self.done = True
            if lift and self.grace_left <= 0:
                self.done = True
        self.lifted = lift
        self.new_stroke = lift

    def start_grace(self, steps):
        self.in_grace = self.finished = True
        self.committed = len(self.recorded)
        self.grace_left = steps

    def result(self):
        kept = self.recorded[:self.committed] if self.finished else self.recorded
        return (torch.stack(kept) if kept else torch.zeros(0, 3), self.finished)
