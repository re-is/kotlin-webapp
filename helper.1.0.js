const log = (t) => { console.log('#app|API|  ' + t) };



if (WND.trustedTypes && WND.trustedTypes.createPolicy) {
	WND.trustedTypes.createPolicy('default', { createHTML: (string, sink) => string });
}

function newHTML(text) {
	return (WND.trustedTypes ? WND.trustedTypes.defaultPolicy.createHTML(text) : text);
}



if (typeof Object.prototype.params !== 'function')
	Object.defineProperty(Object.prototype, 'params', {
		value: function(object) {
			if (object && typeof object === 'object') {
				for (const key in object) {
					if (Object.prototype.hasOwnProperty.call(object, key)) {
						this[key] = object[key];
					}
				}
			}
			return this;
		},
		writable: true,
		configurable: true
	});

if (typeof Element.prototype.apply !== 'function')
	Object.defineProperty(Element.prototype, 'apply', {
		value: function(block) {
			if (typeof block === 'function') {
				block.call(this, this);
			}
			return this;
		},
		writable: true,
		configurable: true
	});
