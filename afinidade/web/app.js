const $ = (selector) => document.querySelector(selector);
const labels = ['Não conheço ou ainda não avaliei', 'Não gosto', 'Gosto muito pouco', 'Gosto', 'Gosto muito'];
const number = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2, minimumFractionDigits: 2 });
let catalog,
    active,
    draft = [],
    busy = false;
function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}
function notice(message, error = false) {
    $('#notice').textContent = message;
    $('#notice').hidden = !message;
    $('#notice').classList.toggle('error', error);
}
function dirty() {
    return active && draft.some((rating, index) => rating !== active.ratings[index]);
}
function confirmDiscard() {
    if (!dirty()) return Promise.resolve(true);
    setBusy(true);
    $('#discard-prompt').hidden = false;
    $('#discard-no').focus();
    return new Promise((resolve) => {
        function finish(discard) {
            $('#discard-prompt').hidden = true;
            $('#discard-yes').onclick = null;
            $('#discard-no').onclick = null;
            setBusy(false);
            resolve(discard);
        }
        $('#discard-yes').onclick = () => finish(true);
        $('#discard-no').onclick = () => finish(false);
    });
}
function setBusy(value) {
    busy = value;
    $('#profile').disabled = value || !catalog?.users.length;
    $('#neighbors-count').disabled = value || !active;
    $('#save').disabled = value || !active || !catalog?.artists.length;
    $('#reload').disabled = value;
    document.querySelectorAll('.rating-choice input').forEach((input) => {
        input.disabled = value;
    });
    $('#ratings-form').setAttribute('aria-busy', String(value));
}
async function api(path, options) {
    const response = await fetch(`/api${path}`, options);
    let data;
    try {
        data = await response.json();
    } catch {
        throw new Error('Resposta inválida do servidor web. Tente recarregar.');
    }
    if (!response.ok)
        throw new Error(data.message || `Não foi possível concluir a operação (${response.status}).`);
    return data;
}
function progress() {
    $('#progress').textContent =
        `${draft.filter((x) => x > 0).length} de ${catalog.artists.length} artistas avaliados${dirty() ? ' · não salvo' : ''}`;
}
function renderRatings() {
    const fragment = document.createDocumentFragment();
    catalog.artists.forEach((artist, index) => {
        const row = element('div', 'rating-row');
        row.append(element('span', 'artist-number', String(artist.id).padStart(2, '0')));
        const name = element('span', 'artist-name', artist.name);
        name.id = `artist-${artist.id}`;
        row.append(name);
        const options = element('div', 'rating-options');
        options.setAttribute('role', 'radiogroup');
        options.setAttribute('aria-labelledby', name.id);
        labels.forEach((description, rating) => {
            const label = element('label', 'rating-choice');
            label.title = `${rating} — ${description}`;
            const input = element('input');
            input.type = 'radio';
            input.name = `rating-${artist.id}`;
            input.value = rating;
            input.checked = draft[index] === rating;
            input.setAttribute('aria-label', `${artist.name}: ${rating} — ${description}`);
            input.addEventListener('change', () => {
                draft[index] = rating;
                progress();
                notice('Você tem alterações não salvas. Salve para atualizar suas recomendações.');
            });
            label.append(input, element('span', '', String(rating)));
            options.append(label);
        });
        row.append(options);
        fragment.append(row);
    });
    $('#ratings').replaceChildren(fragment);
    progress();
}
function resultRow(index, title, subtitle) {
    const details = element('details', 'result-item');
    const summary = element('summary');
    const copy = element('span', 'result-copy');
    copy.append(element('strong', '', title), element('small', '', subtitle));
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'chevron');
    svg.setAttribute('viewBox', '0 0 20 20');
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', 'm7 3 7 7-7 7');
    svg.append(path);
    summary.append(element('span', 'rank', String(index + 1).padStart(2, '0')), copy, svg);
    const body = element('div', 'detail-body');
    details.append(summary, body);
    return { details, body };
}
function renderResults(result) {
    const recommendations = $('#recommendations');
    recommendations.replaceChildren();
    result.recommendations.forEach((item, index) => {
        const { details, body } = resultRow(
            index,
            item.artistName,
            `Nota estimada: ${number.format(item.score)} de 4`,
        );
        body.append(element('p', '', 'Média ponderada das avaliações abaixo. Peso = 1/(1 + distância).'));
        const list = element('ul');
        item.contributions.forEach((c) =>
            list.append(element('li', '', `${c.name}: nota ${c.rating} · peso ${number.format(c.weight)}`)),
        );
        body.append(list);
        recommendations.append(details);
    });
    if (!result.recommendations.length) {
        const text = active.ratings.every((x) => x > 0)
            ? 'Você já avaliou todos os artistas. Marque 0 em um artista que ainda não conhece para receber sugestões.'
            : !result.neighbors.length
              ? 'Avalie alguns artistas para encontrar perfis com gostos em comum.'
              : 'Ainda não há sugestões com nota estimada a partir de 3. Experimente ampliar a quantidade de vizinhos.';
        recommendations.append(element('p', 'empty', text));
    }
    const neighbors = $('#neighbors');
    neighbors.replaceChildren();
    result.neighbors.forEach((item, index) => {
        const { details, body } = resultRow(
            index,
            item.name,
            `Distância: ${number.format(item.distance)} · ${item.commonRatings} artistas em comum`,
        );
        body.append(element('p', '', 'Somente notas diferentes de zero nos dois perfis:'));
        const table = element('table', 'detail-table');
        const head = element('thead'),
            tr = element('tr');
        ['Artista', 'Você', 'Vizinho', 'Dif.²'].forEach((label) => {
            const th = element('th', '', label);
            th.scope = 'col';
            tr.append(th);
        });
        head.append(tr);
        table.append(head);
        const tbody = element('tbody');
        item.comparisons.forEach((term) => {
            const row = element('tr');
            [term.artistName, term.targetRating, term.neighborRating, term.squaredDifference].forEach(
                (value) => row.append(element('td', '', value)),
            );
            tbody.append(row);
        });
        table.append(tbody);
        body.append(table);
        const sum = item.comparisons.reduce((value, term) => value + term.squaredDifference, 0);
        body.append(element('p', '', `d = √${sum} = ${number.format(item.distance)}`));
        neighbors.append(details);
    });
    if (!result.neighbors.length)
        neighbors.append(
            element(
                'p',
                'empty',
                'Nenhum perfil com avaliações em comum. Notas zero não criam similaridade.',
            ),
        );
}
async function loadRecommendations() {
    const result = await api(`/users/${active.id}/recommendations?k=${$('#neighbors-count').value}&limit=15`);
    renderResults(result);
    if (result.version !== active.version)
        notice('Outro cliente alterou este perfil. Recarregue os dados antes de editar.', true);
}
async function loadCatalog(selectedId) {
    setBusy(true);
    try {
        catalog = await api('/catalog');
        $('#profile').replaceChildren(
            ...catalog.users.map((user) => {
                const option = element('option', '', user.name);
                option.value = user.id;
                return option;
            }),
        );
        active = catalog.users.find((user) => user.id === Number(selectedId)) || catalog.users[0];
        $('#catalog-count').textContent = `${catalog.artists.length} artistas · ${catalog.users.length} perfis`;
        if (!active) {
            draft = [];
            $('#profile').replaceChildren(element('option', '', 'Nenhum perfil cadastrado'));
            $('#ratings').replaceChildren(element('p', 'empty', 'Nenhum usuário cadastrado. Ainda não há avaliações.'));
            $('#recommendations').replaceChildren(element('p', 'empty', 'As recomendações aparecerão quando houver perfis com avaliações em comum.'));
            $('#neighbors').replaceChildren(element('p', 'empty', 'Nenhum perfil disponível para comparação.'));
            $('#progress').textContent = '';
            notice('');
            return;
        }
        $('#profile').value = active.id;
        draft = [...active.ratings];
        renderRatings();
        notice('');
        await loadRecommendations();
    } catch (error) {
        notice(error.message || 'Falha de conexão. Tente recarregar os dados.', true);
    } finally {
        setBusy(false);
    }
}
$('#ratings-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy || !active) return;
    setBusy(true);
    $('#save').textContent = 'Salvando…';
    let saved = false;
    try {
        active = await api(`/users/${active.id}/ratings`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ version: active.version, ratings: draft }),
        });
        saved = true;
        catalog.users = catalog.users.map((user) => (user.id === active.id ? active : user));
        progress();
        notice('Avaliações salvas. Suas descobertas foram atualizadas.');
        await loadRecommendations();
    } catch (error) {
        notice(
            `${saved ? 'Avaliações salvas, mas não foi possível atualizar as recomendações. ' : ''}${error.message}`,
            true,
        );
    } finally {
        $('#save').textContent = 'Salvar avaliações';
        setBusy(false);
    }
});
$('#profile').addEventListener('change', async () => {
    const selectedId = $('#profile').value;
    if (!(await confirmDiscard())) {
        $('#profile').value = active.id;
        return;
    }
    await loadCatalog(selectedId);
});
$('#neighbors-count').addEventListener('change', async () => {
    if (!active || busy) return;
    setBusy(true);
    try {
        await loadRecommendations();
    } catch (error) {
        notice(error.message, true);
    } finally {
        setBusy(false);
    }
});
$('#reload').addEventListener('click', async () => {
    if (!(await confirmDiscard())) return;
    loadCatalog(active?.id);
});
window.addEventListener('beforeunload', (event) => {
    if (dirty()) {
        event.preventDefault();
        event.returnValue = '';
    }
});
loadCatalog();
