document.addEventListener('DOMContentLoaded', function() {
    const addForm = document.getElementById('addExpenseForm');
    const splitTypeRadios = document.querySelectorAll('input[name="splitType"]');
    const splitDetailsGroup = document.getElementById('splitDetailsGroup');
    const splitDetailsLabel = document.getElementById('splitDetailsLabel');
    const splitDetailsTextarea = document.getElementById('splitDetails');
    const expensesList = document.getElementById('expensesList');
    const balancesTableBody = document.getElementById('balancesTable').querySelector('tbody');
    const settlementsList = document.getElementById('settlementsList');
    const computeSettlementsBtn = document.getElementById('computeSettlements');

    // Handle split type change
    splitTypeRadios.forEach(radio => {
        radio.addEventListener('change', function() {
            const type = this.value;
            splitDetailsGroup.style.display = type === 'equal' ? 'none' : 'block';
            if (type === 'exact') {
                splitDetailsLabel.textContent = 'Enter exact amounts (one per line, format: Name:Amount)';
                splitDetailsTextarea.placeholder = 'John:100.00\nJane:150.00\nBob:50.00';
            } else if (type === 'percentage') {
                splitDetailsLabel.textContent = 'Enter percentages (one per line, format: Name:Percent, total 100)';
                splitDetailsTextarea.placeholder = 'John:33.33\nJane:33.33\nBob:33.34';
            } else if (type === 'shares') {
                splitDetailsLabel.textContent = 'Enter shares (one per line, format: Name:ShareCount)';
                splitDetailsTextarea.placeholder = 'John:2\nJane:1\nBob:1';
            }
        });
    });

    // Add expense form submit
    addForm.addEventListener('submit', function(e) {
        e.preventDefault();

        const payer = document.getElementById('payer').value.trim();
        const total = parseFloat(document.getElementById('total').value);
        const participantsText = document.getElementById('participants').value.trim();
        const participants = participantsText.split('\n').map(p => p.trim()).filter(p => p);
        const splitType = document.querySelector('input[name="splitType"]:checked').value;
        let splitDetails = {};

        if (!payer || isNaN(total) || total <= 0 || participants.length === 0) {
            alert('Please provide valid payer, total, and participants.');
            return;
        }

        if (splitType !== 'equal') {
            const detailsText = splitDetailsTextarea.value.trim();
            if (!detailsText) {
                alert('Please provide split details for unequal split.');
                return;
            }
            const detailsLines = detailsText.split('\n').map(line => line.trim()).filter(line => line);
            for (let line of detailsLines) {
                const match = line.match(/^(.+?):\s*([\d.]+)$/);
                if (match) {
                    const name = match[1].trim();
                    const value = parseFloat(match[2]);
                    if (name && !isNaN(value)) {
                        splitDetails[name] = value;
                    }
                }
            }
            if (Object.keys(splitDetails).length !== participants.length) {
                alert('Split details must match all participants.');
                return;
            }
        }

        const requestData = {
            payer: payer,
            total: total,
            participants: participants,
            splitType: splitType,
            splitDetails: splitDetails
        };

        fetch('/addExpense', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(requestData)
        })
        .then(response => response.json())
        .then(data => {
            if (data.error) {
                alert('Error adding expense: ' + data.error);
            } else {
                alert('Expense added successfully!');
                addForm.reset();
                splitDetailsGroup.style.display = 'none';
                loadExpenses();
                loadBalances();
            }
        })
        .catch(error => {
            alert('Error adding expense.');
            console.error('Error:', error);
        });
    });

    // Compute settlements button
    computeSettlementsBtn.addEventListener('click', function() {
        fetch('/settlements')
        .then(response => response.json())
        .then(data => {
            const settlements = data.settlements || [];
            let html = '';
            if (settlements.length === 0) {
                html = '<li>No settlements needed.</li>';
            } else {
                settlements.forEach(sett => {
                    html += `<li>${sett.from} pays ${sett.to} $${sett.amount.toFixed(2)}</li>`;
                });
            }
            settlementsList.innerHTML = html;
        })
        .catch(error => {
            console.error('Error fetching settlements:', error);
        });
    });

    // Load functions
    function loadExpenses() {
        fetch('/expenses')
        .then(response => response.json())
        .then(data => {
            const expenses = data.expenses || [];
            let html = '';
            if (expenses.length === 0) {
                html = '<li>No expenses added yet.</li>';
            } else {
                expenses.forEach(exp => {
                    html += `<li>Payer: ${exp.payer}, Total: $${exp.total.toFixed(2)}, Type: ${exp.splitType}, Participants: ${exp.participants.join(', ')}</li>`;
                });
            }
            expensesList.innerHTML = html;
        })
        .catch(error => {
            console.error('Error fetching expenses:', error);
        });
    }

    function loadBalances() {
        fetch('/balances')
        .then(response => response.json())
        .then(data => {
            const balances = data.balances || {};
            let html = '';
            Object.entries(balances).forEach(([person, bal]) => {
                const sign = bal >= 0 ? 'owes you' : 'you owe';
                html += `<tr><td>${person}</td><td>$${Math.abs(bal).toFixed(2)} (${bal >= 0 ? '+' : '-'})</td></tr>`;
            });
            balancesTableBody.innerHTML = html;
        })
        .catch(error => {
            console.error('Error fetching balances:', error);
        });
    }

    // Initial load
    loadExpenses();
    loadBalances();
});
