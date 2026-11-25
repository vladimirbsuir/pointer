INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-00000000001a', 'desktop_tree',
     'Read the accessibility tree of the display: the controls of the focused window (scope focused, the default) or of every window (scope all), each with an id, its role, name, text, value, states such as focused, checked or disabled, and bounds as [x, y, width, height]. Click an element with desktop_click and its elementId instead of coordinates: the click lands on the center of the element where it is now, and fails with element_not_found when the element has gone. Ids stay the same while an element stays. GTK, Qt, Java Swing, WebKitGTK (Tauri) and pages in pointer-chromium have a tree; Electron applications have one when started with --force-renderer-accessibility; Tk and plain X11 applications have none, so use coordinates there.',
     '{"type":"object","properties":{"scope":{"type":"string","enum":["focused","all"],"description":"focused lists the window that has the focus, all lists every window. focused by default."},"maxNodes":{"type":"integer","minimum":1,"maximum":500,"description":"Most elements to return, 150 by default."},"maxDepth":{"type":"integer","minimum":1,"maximum":60,"description":"Deepest nesting level to return, 30 by default."}},"additionalProperties":false}',
     'read', 'desktop_tree', '{agent}', 'allow', 'builtin', now(), now());

UPDATE tools
SET description = 'Click at a point of the display, or on an element of a desktop_tree result by its elementId; give either x and y or elementId. button is left by default; clicks 2 is a double click and 3 a triple click; modifiers are held during the click. Returns a screenshot of the result.',
    parameters_schema = '{"type":"object","properties":{"x":{"type":"integer","description":"Horizontal position in screenshot coordinates."},"y":{"type":"integer","description":"Vertical position in screenshot coordinates."},"elementId":{"type":"string","description":"Id of an element from desktop_tree, instead of x and y."},"button":{"type":"string","enum":["left","middle","right"]},"clicks":{"type":"integer","minimum":1,"maximum":3},"modifiers":{"type":"array","items":{"type":"string","enum":["ctrl","shift","alt","super"]}},"observe":{"type":"boolean","description":"Return a screenshot after the action, true by default. Set it to false for a series of inputs whose effect you do not need to see in between."}},"additionalProperties":false}',
    updated_at = now()
WHERE id = '01930000-0000-7000-8000-00000000000f';

UPDATE tools
SET description = description || ' When the application has an accessibility tree, desktop_tree and desktop_click with an elementId are more precise than coordinates read from a screenshot.',
    updated_at = now()
WHERE id = '01930000-0000-7000-8000-000000000016';
