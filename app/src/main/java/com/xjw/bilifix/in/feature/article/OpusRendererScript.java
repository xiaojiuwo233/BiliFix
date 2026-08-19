package com.xjw.bilifix.in.feature.article;

import static com.xjw.bilifix.in.core.ModuleConstants.PROJECT_NEW_ISSUE_URL;

import org.json.JSONObject;

public final class OpusRendererScript {
    private OpusRendererScript() {
    }

    public static String source(boolean imagePreviewEnabled, String articleUrl) {
        return """
                (() => {
                  try {
                    const imagePreviewEnabled = __BILIFIX_IMAGE_PREVIEW_ENABLED__;
                    const articleUrl = __BILIFIX_ARTICLE_URL__;
                    const issueUrl = __BILIFIX_ISSUE_URL__;
                    const state = window.__INITIAL_STATE__;
                    const info = state && state.readInfo;
                    const opus = info && info.opus;
                    const paragraphs = opus && opus.content && opus.content.paragraphs;
                    const root = document.querySelector('.eva3-content');
                    if (!root) return 'bilifix:no-root';
                    if (!Array.isArray(paragraphs) || paragraphs.length === 0) {
                      return 'bilifix:no-opus-data';
                    }
                    // Keyed by article URL: a recycled WebView carrying another article's
                    // marker must still re-render instead of showing the previous content.
                    if (root.dataset.bilifixRendered === articleUrl) {
                      return 'bilifix:already-rendered';
                    }

                    let style = document.getElementById('bilifix-opus-style');
                    if (!style) {
                      style = document.createElement('style');
                      style.id = 'bilifix-opus-style';
                      style.textContent = `
                        .bilifix-opus-reader{box-sizing:border-box;padding:8px 18px 28px;color:#2f3238;font-size:16px;line-height:1.75;word-break:break-word;overflow-wrap:anywhere}
                        .night-mode .bilifix-opus-reader{color:#d0d3d7}
                        .bilifix-opus-paragraph{margin:0 0 12px;min-height:8px;white-space:pre-wrap}
                        .bilifix-opus-paragraph.center{text-align:center}
                        .bilifix-opus-quote{margin:4px 0 14px;padding:6px 10px;border-left:4px solid #c9ccd0;background:rgba(128,128,128,.10)}
                        .bilifix-opus-heading{margin:20px 0 10px;font-size:20px;line-height:1.45;font-weight:700}
                        .bilifix-opus-image{display:block;width:100%;height:auto;margin:8px 0 14px;border-radius:4px;background:rgba(128,128,128,.08)}
                        .bilifix-opus-image.bilifix-previewable{cursor:pointer;-webkit-tap-highlight-color:transparent;touch-action:manipulation}
                        .bilifix-opus-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;margin:8px 0 14px}
                        .bilifix-opus-grid .bilifix-opus-image{height:160px;margin:0;object-fit:cover}
                        .bilifix-opus-list{margin:0 0 14px;padding-left:24px}
                        .bilifix-opus-list li{margin:4px 0}
                        .bilifix-opus-card{display:flex;gap:10px;align-items:center;margin:8px 0 14px;padding:10px;border-radius:6px;background:rgba(128,128,128,.10);color:inherit;text-decoration:none}
                        .bilifix-opus-card img{width:104px;height:65px;object-fit:cover;border-radius:4px;flex:none}
                        .bilifix-opus-card-copy{min-width:0;display:flex;flex-direction:column;gap:3px}
                        .bilifix-opus-card-title{font-weight:600;line-height:1.4}
                        .bilifix-opus-card-desc{font-size:13px;line-height:1.4;color:#9499a0}
                        .bilifix-opus-code{box-sizing:border-box;margin:8px 0 14px;padding:12px;overflow:auto;border-radius:6px;background:rgba(128,128,128,.12);font:13px/1.55 monospace;white-space:pre-wrap}
                        .bilifix-opus-link{color:#00aeec;text-decoration:none}
                        .bilifix-opus-emoji{width:20px;height:20px;vertical-align:text-bottom}
                        .bilifix-opus-divider{border:0;border-top:1px solid rgba(128,128,128,.28);margin:18px 0}
                        .bilifix-opus-meta{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:28px 0 0;color:#9499a0;font-size:12px;line-height:1.6}
                        .bilifix-opus-meta-left{display:flex;align-items:center;gap:14px;min-width:0;white-space:nowrap}
                        .bilifix-opus-meta-right{min-width:0;text-align:right}
                        .night-mode .bilifix-opus-meta{color:#8b8f96}
                        .bilifix-opus-footer{margin:20px 0 0;color:#9499a0;font-size:12px;line-height:1.6;text-align:right}
                        .night-mode .bilifix-opus-footer{color:#8b8f96}
                        .bilifix-opus-feedback{color:inherit;text-decoration:underline;text-underline-offset:2px;-webkit-tap-highlight-color:transparent}
                      `;
                      document.head.appendChild(style);
                    }

                    const dark = document.documentElement.classList.contains('night-mode');
                    const make = (tag, className) => {
                      const element = document.createElement(tag);
                      if (className) element.className = className;
                      return element;
                    };
                    const validColor = value => typeof value === 'string'
                      && /^#[0-9a-f]{6}$/i.test(value);
                    const applyTextStyle = (element, source) => {
                      const styleData = source && source.style || {};
                      if (styleData.bold) element.style.fontWeight = '700';
                      if (styleData.italic) element.style.fontStyle = 'italic';
                      if (styleData.strikethrough) element.style.textDecoration = 'line-through';
                      const color = dark ? source && source.dark_color : source && source.color;
                      if (validColor(color)) element.style.color = color;
                      const size = Number(source && source.font_size);
                      if (size > 0 && size <= 40) element.style.fontSize = `${size}px`;
                    };
                    const appendNodes = (parent, nodes) => {
                      (Array.isArray(nodes) ? nodes : []).forEach(node => {
                        if (node && node.word) {
                          const span = make('span');
                          span.textContent = node.word.words || '';
                          applyTextStyle(span, node.word);
                          parent.appendChild(span);
                          return;
                        }
                        if (node && node.rich) {
                          const rich = node.rich;
                          if (rich.emoji && rich.emoji.url) {
                            const image = make('img', 'bilifix-opus-emoji');
                            image.src = rich.emoji.url;
                            image.alt = rich.orig_text || rich.text || '';
                            parent.appendChild(image);
                            return;
                          }
                          const linked = typeof rich.jump_url === 'string'
                            && rich.jump_url.length > 0;
                          const span = make(linked ? 'a' : 'span',
                            linked ? 'bilifix-opus-link' : '');
                          span.textContent = rich.text || rich.orig_text || '';
                          applyTextStyle(span, rich);
                          if (linked) span.href = rich.jump_url;
                          parent.appendChild(span);
                          return;
                        }
                        if (node && node.formula) {
                          const span = make('span');
                          span.textContent = node.formula.latex_content || '';
                          parent.appendChild(span);
                        }
                      });
                    };
                    const normalizePictures = picture => {
                      if (!picture) return [];
                      if (Array.isArray(picture.pics)) return picture.pics;
                      return picture.url ? [picture] : [];
                    };
                    const gallery = [];
                    const openGallery = (event, galleryIndex) => {
                      event.preventDefault();
                      event.stopPropagation();
                      const bridge = window.BiliFixBridge;
                      if (!bridge || typeof bridge.openImages !== 'function') return;
                      try {
                        bridge.openImages(JSON.stringify(gallery), galleryIndex);
                      } catch (error) {
                        console.error('[BiliFix] image preview failed', error);
                      }
                    };
                    const appendPictures = (parent, pictures) => {
                      const usable = pictures.filter(pic => pic && pic.url);
                      if (!usable.length) return;
                      const holder = usable.length > 1
                        ? make('div', 'bilifix-opus-grid') : parent;
                      usable.forEach(pic => {
                        const image = make('img', 'bilifix-opus-image');
                        const url = String(pic.url).replace(/^http:/, 'https:');
                        const galleryIndex = gallery.length;
                        gallery.push({
                          url,
                          width: Math.max(0, Number(pic.width) || 0),
                          height: Math.max(0, Number(pic.height) || 0)
                        });
                        image.src = url;
                        image.alt = '';
                        image.loading = 'lazy';
                        image.dataset.bilifixGalleryIndex = String(galleryIndex);
                        if (imagePreviewEnabled) {
                          image.classList.add('bilifix-previewable');
                          image.setAttribute('role', 'button');
                          image.tabIndex = 0;
                          image.addEventListener('click', event => {
                            openGallery(event, galleryIndex);
                          });
                          image.addEventListener('keydown', event => {
                            if (event.key === 'Enter' || event.key === ' ') {
                              openGallery(event, galleryIndex);
                            }
                          });
                        }
                        holder.appendChild(image);
                      });
                      if (holder !== parent) parent.appendChild(holder);
                    };
                    const firstObjectValue = object => {
                      if (!object) return null;
                      const keys = ['ugc', 'common', 'live', 'opus', 'music', 'vote', 'goods', 'item_null'];
                      for (const key of keys) if (object[key]) return object[key];
                      return null;
                    };
                    const appendCard = (parent, paragraph) => {
                      const card = paragraph && paragraph.link_card && paragraph.link_card.card;
                      if (!card) return;
                      const data = firstObjectValue(card) || card;
                      const anchor = make('a', 'bilifix-opus-card');
                      const url = data.jump_url || card.jump_url;
                      if (url) anchor.href = url;
                      const cover = data.cover || data.head_icon;
                      if (cover) {
                        const image = make('img');
                        image.src = String(cover).replace(/^http:/, 'https:');
                        image.alt = '';
                        anchor.appendChild(image);
                      }
                      const copy = make('span', 'bilifix-opus-card-copy');
                      const title = make('span', 'bilifix-opus-card-title');
                      title.textContent = data.title || data.name || data.desc || data.text
                        || card.type || '相关内容';
                      copy.appendChild(title);
                      const description = data.desc_second || data.desc1 || data.desc2
                        || data.brief || data.label || data.head_text;
                      if (description) {
                        const desc = make('span', 'bilifix-opus-card-desc');
                        desc.textContent = description;
                        copy.appendChild(desc);
                      }
                      anchor.appendChild(copy);
                      parent.appendChild(anchor);
                    };
                    const appendParagraph = (parent, paragraph) => {
                      const type = Number(paragraph && paragraph.para_type);
                      if (type === 1 || type === 4) {
                        const block = make('div', type === 4
                          ? 'bilifix-opus-paragraph bilifix-opus-quote'
                          : 'bilifix-opus-paragraph');
                        if (paragraph.align === 1 || paragraph.format && paragraph.format.align === 1) {
                          block.classList.add('center');
                        }
                        appendNodes(block, paragraph.text && paragraph.text.nodes);
                        parent.appendChild(block);
                      } else if (type === 2) {
                        appendPictures(parent, normalizePictures(paragraph.pic));
                      } else if (type === 3) {
                        const pictures = normalizePictures(paragraph.line && paragraph.line.pic);
                        if (pictures.length) appendPictures(parent, pictures);
                        else parent.appendChild(make('hr', 'bilifix-opus-divider'));
                      } else if (type === 5) {
                        const listData = paragraph.list || {};
                        const list = make(listData.style === 2 ? 'ol' : 'ul', 'bilifix-opus-list');
                        (listData.items || []).forEach(item => {
                          const row = make('li');
                          appendNodes(row, item.nodes);
                          list.appendChild(row);
                        });
                        parent.appendChild(list);
                      } else if (type === 6) {
                        appendCard(parent, paragraph);
                      } else if (type === 7 && paragraph.code) {
                        const code = make('pre', 'bilifix-opus-code');
                        code.textContent = paragraph.code.content || '';
                        parent.appendChild(code);
                      } else if (type === 8) {
                        const heading = make('h2', 'bilifix-opus-heading');
                        appendNodes(heading, paragraph.heading && paragraph.heading.nodes);
                        parent.appendChild(heading);
                      } else if (paragraph && paragraph.text) {
                        const fallback = make('div', 'bilifix-opus-paragraph');
                        appendNodes(fallback, paragraph.text.nodes);
                        parent.appendChild(fallback);
                      }
                    };

                    const reader = make('article', 'bilifix-opus-reader');
                    const cover = opus.article && Array.isArray(opus.article.cover)
                      ? opus.article.cover[0] : null;
                    if (cover && cover.url) appendPictures(reader, [cover]);
                    paragraphs.forEach(paragraph => appendParagraph(reader, paragraph));
                    const formatViewCount = value => {
                      const count = Math.max(0, Number(value) || 0);
                      if (count < 10000) return String(Math.floor(count));
                      const wan = Math.floor(count / 1000) / 10;
                      return `${wan.toFixed(wan % 1 === 0 ? 0 : 1)}万`;
                    };
                    const articleId = Math.max(0, Number(info.id) || 0);
                    const viewCount = info.stats && Number(info.stats.view);
                    if (articleId > 0 || Number.isFinite(viewCount)) {
                      const meta = make('div', 'bilifix-opus-meta');
                      const metaLeft = make('span', 'bilifix-opus-meta-left');
                      if (Number.isFinite(viewCount)) {
                        const views = make('span');
                        views.textContent = `${formatViewCount(viewCount)}浏览`;
                        metaLeft.appendChild(views);
                      }
                      if (articleId > 0) {
                        const cvid = make('span');
                        cvid.textContent = `cv${articleId}`;
                        metaLeft.appendChild(cvid);
                      }
                      meta.appendChild(metaLeft);
                      let authority = '';
                      if (Number(info.reprint) === 0 && Number(info.type) !== 2) {
                        authority = '本文禁止转载或摘编';
                      } else if (Number(info.original) === 1 && Number(info.type) !== 2) {
                        authority = '本文为我原创';
                      }
                      if (authority) {
                        const metaRight = make('span', 'bilifix-opus-meta-right');
                        metaRight.textContent = authority;
                        meta.appendChild(metaRight);
                      }
                      reader.appendChild(meta);
                    }
                    const footer = make('footer', 'bilifix-opus-footer');
                    footer.appendChild(document.createTextNode('本专栏由BiliFix修复与渲染'));
                    footer.appendChild(make('br'));
                    footer.appendChild(document.createTextNode('排版出错？'));
                    const feedback = make('a', 'bilifix-opus-feedback');
                    feedback.textContent = '点击复制链接并反馈';
                    feedback.href = issueUrl;
                    feedback.addEventListener('click', event => {
                      const bridge = window.BiliFixBridge;
                      if (!bridge || typeof bridge.reportArticleIssue !== 'function') return;
                      event.preventDefault();
                      try {
                        bridge.reportArticleIssue(articleUrl);
                      } catch (error) {
                        console.error('[BiliFix] article feedback failed', error);
                      }
                    });
                    footer.appendChild(feedback);
                    reader.appendChild(footer);
                    while (root.firstChild) root.removeChild(root.firstChild);
                    root.appendChild(reader);
                    root.dataset.bilifixRendered = articleUrl;
                    return JSON.stringify({
                      status: 'rendered',
                      cvid: info.id || 0,
                      dynamicId: state.dynamicId || String(opus.opus_id || ''),
                      paragraphs: paragraphs.length,
                      images: gallery.length,
                      imagePreview: imagePreviewEnabled
                    });
                  } catch (error) {
                    return `bilifix:error:${error && error.message || error}`;
                  }
                })()
                """
                .replace("__BILIFIX_IMAGE_PREVIEW_ENABLED__",
                        imagePreviewEnabled ? "true" : "false")
                .replace("__BILIFIX_ARTICLE_URL__", JSONObject.quote(articleUrl))
                .replace("__BILIFIX_ISSUE_URL__", JSONObject.quote(PROJECT_NEW_ISSUE_URL));
    }
}
